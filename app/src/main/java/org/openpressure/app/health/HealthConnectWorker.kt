package org.openpressure.app.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.openpressure.app.PressureApp

/** WorkManager persists wake-ups across process death and restarts. */
class HealthConnectWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as PressureApp).healthConnect()
        return if (sync.exportPending()) Result.success() else Result.retry()
    }
}
