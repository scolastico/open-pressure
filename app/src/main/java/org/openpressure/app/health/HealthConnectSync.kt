package org.openpressure.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openpressure.app.data.ExperimentalConsent
import org.openpressure.app.data.ReadingStore
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** Opt-in, write-only export. The local database remains the source of truth. */
class HealthConnectSync(context: Context, private val store: ReadingStore) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val stateLock = Any()
    private val exportLock = Mutex()
    private var generation = 0L
    private var changeWakeQueued = false
    @Volatile private var statusText = if (enabled()) "Checking Health Connect…" else disabledStatus()
    private val permissions = setOf(HealthPermission.getWritePermission(BloodPressureRecord::class))
    private val trailingChange = Runnable {
        synchronized(stateLock) { changeWakeQueued = false }
        if (enabled()) enqueue(0)
    }

    fun enabled(): Boolean = ExperimentalConsent.accepted(context) && preferences.getBoolean("enabled", false) &&
        preferences.getBoolean("consent", false) && selectedUser() in 1..2
    fun selectedUser(): Int = preferences.getInt("bank", 0)
    fun status(): String = statusText

    fun availability(): Int = if (Build.VERSION.SDK_INT < 28) HealthConnectClient.SDK_UNAVAILABLE else try {
        HealthConnectClient.getSdkStatus(context)
    } catch (_: RuntimeException) { HealthConnectClient.SDK_UNAVAILABLE }

    fun listen(listener: Runnable) { listeners.add(listener) }
    fun unlisten(listener: Runnable) { listeners.remove(listener) }

    /** Called only after the settings screen explains the selected bank and the user accepts. */
    fun prepareEnable(bank: Int) {
        require(bank in 1..2) { "Choose your own user bank, 1 or 2" }
        synchronized(stateLock) {
            generation++
            preferences.edit().putInt("bank", bank).putBoolean("enabled", false)
                .putBoolean("consent", true).putBoolean("permission_pending", true)
                .putBoolean("permission_revoked", false).apply()
        }
        cancelWork()
        publish("Waiting for blood pressure write permission for user bank $bank.")
    }

    fun permissionIntent(): Intent? = if (availability() == HealthConnectClient.SDK_AVAILABLE) {
        PermissionController.createRequestPermissionResultContract().createIntent(context, permissions)
    } else null

    fun completePermissionRequest() {
        val requestGeneration = synchronized(stateLock) { generation }
        scope.launch {
            try {
                val granted = availability() == HealthConnectClient.SDK_AVAILABLE && hasPermission()
                synchronized(stateLock) {
                    if (generation != requestGeneration || !preferences.getBoolean("permission_pending", false)) return@launch
                    val accepted = granted && ExperimentalConsent.accepted(context) &&
                        preferences.getBoolean("consent", false) && selectedUser() in 1..2
                    preferences.edit().putBoolean("permission_pending", false).putBoolean("enabled", accepted).apply()
                }
                if (enabled()) {
                    schedulePeriodic()
                    publishSummary("Automatic export enabled")
                    enqueue(0)
                } else publish("Automatic export is off. Blood pressure write permission was not granted.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish("Could not check Health Connect permission. Enable export again to retry.") }
        }
    }

    fun disable() {
        synchronized(stateLock) {
            generation++
            preferences.edit().putBoolean("enabled", false).putBoolean("consent", false)
                .putBoolean("permission_pending", false).putBoolean("permission_revoked", false).apply()
            changeWakeQueued = false
        }
        handler.removeCallbacks(trailingChange)
        cancelWork()
        publish("Automatic export is off. Existing Health Connect copies are retained.")
    }

    fun refresh() {
        scope.launch {
            try {
                if (!enabled()) {
                    publish(disabledStatus())
                    return@launch
                }
                if (!checkAvailability()) return@launch
                val token = snapshot()
                if (!authorized(token)) return@launch
                schedulePeriodic()
                publishSummary("Automatic export enabled")
                enqueue(0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish("Health Connect is temporarily unavailable. Pending readings will retry.") }
        }
    }

    fun syncNow() {
        if (!enabled()) return
        scope.launch {
            store.retryHealthSkipped(selectedUser())
            publishSummary("Export queued")
            enqueue(0)
        }
    }

    /** Queue immediately for process durability, and append one trailing wake-up after a burst. */
    fun onRecordsChanged() {
        if (!enabled()) return
        synchronized(stateLock) {
            preferences.edit().putLong("last_change", System.currentTimeMillis()).apply()
            if (!changeWakeQueued) {
                changeWakeQueued = true
                enqueue(DEBOUNCE_MS)
            }
            handler.removeCallbacks(trailingChange)
            handler.postDelayed(trailingChange, DEBOUNCE_MS)
        }
    }

    fun manageIntent(): Intent = HealthConnectClient.getHealthConnectManageDataIntent(context)

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
        .putExtra("overlay", true).putExtra("callerId", context.packageName)

    internal suspend fun exportPending(): Boolean = exportLock.withLock {
        if (!enabled()) return@withLock true
        while (enabled()) {
            val changed = preferences.getLong("last_change", 0L)
            val now = System.currentTimeMillis()
            // A corrected phone clock must not leave an export sleeping until the old clock catches up.
            val remaining = if (changed > now) 0L else changed + DEBOUNCE_MS - now
            if (remaining <= 0) break
            delay(remaining.coerceAtMost(DEBOUNCE_MS))
        }
        if (!enabled() || !checkAvailability()) return@withLock true
        val token = snapshot()
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val completed = HealthExportRunner(
                authorized = { authorized(token) },
                pending = { store.healthPending(token.bank, 50) },
                current = { reading -> store.hasReadingIdentity(reading.id, reading.fingerprint) },
                prepare = { reading ->
                    HealthConnectRecords.prepare(reading, token.bank, { id, time, offset ->
                        store.prepareHealthExport(reading.id, reading.fingerprint, id, time, offset)
                    })
                },
                insert = { record -> client.insertRecords(listOf(record)); Unit },
                exported = { store.markHealthExported(it.id, it.fingerprint) },
                skipped = { reading, reason -> store.markHealthSkipped(reading.id, reading.fingerprint, reason) },
            ).run()
            if (completed && stillEnabled(token)) publishSummary("Automatic export enabled")
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) {
            pauseForPermission(token)
            true
        } catch (_: Exception) {
            if (stillEnabled(token)) publishSummary("Health Connect export failed; pending readings will retry")
            false
        }
    }

    private data class Token(val bank: Int, val generation: Long)
    private fun snapshot(): Token = synchronized(stateLock) { Token(selectedUser(), generation) }
    private fun stillEnabled(token: Token): Boolean = synchronized(stateLock) {
        enabled() && token.bank == selectedUser() && token.generation == generation
    }

    private suspend fun hasPermission(): Boolean = HealthConnectClient.getOrCreate(context)
        .permissionController.getGrantedPermissions().containsAll(permissions)

    private suspend fun authorized(token: Token): Boolean {
        if (!stillEnabled(token)) return false
        if (!hasPermission()) {
            pauseForPermission(token)
            return false
        }
        return stillEnabled(token)
    }

    private fun pauseForPermission(token: Token) {
        synchronized(stateLock) {
            if (!stillEnabled(token)) return
            generation++
            preferences.edit().putBoolean("enabled", false).putBoolean("permission_pending", false)
                .putBoolean("permission_revoked", true).apply()
        }
        cancelWork()
        publish(disabledStatus())
    }

    private fun disabledStatus(): String = if (preferences.getBoolean("permission_revoked", false))
        "Export paused: blood pressure write permission was revoked. Enable export to grant it again. Existing copies are retained."
        else "Automatic export is off."

    private fun checkAvailability(): Boolean = when (availability()) {
        HealthConnectClient.SDK_AVAILABLE -> true
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            publish("Install or update Health Connect to export pending readings."); false
        }
        else -> { publish("Health Connect is unavailable on this device. Android 9 or newer is required."); false }
    }

    private fun enqueue(delayMs: Long) {
        if (!enabled()) return
        val request = OneTimeWorkRequestBuilder<HealthConnectWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(EXPORT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun schedulePeriodic() {
        if (!enabled()) return
        val request = PeriodicWorkRequestBuilder<HealthConnectWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(RETRY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancelWork() {
        WorkManager.getInstance(context).cancelUniqueWork(EXPORT_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(RETRY_WORK)
    }

    private fun publishSummary(prefix: String) {
        val bank = selectedUser()
        val skipped = store.healthSkippedCount(bank)
        val explanation = if (skipped > 0) " Last skipped: ${store.healthLastError(bank)}. Skipped readings remain local." else ""
        publish("$prefix for user bank $bank. ${store.healthExportedCount(bank)} exported; $skipped skipped.$explanation")
    }

    private fun publish(message: String) {
        statusText = message
        handler.post { listeners.forEach { it.run() } }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L
        const val EXPORT_WORK = "health-connect-export"
        const val RETRY_WORK = "health-connect-retry"
    }
}
