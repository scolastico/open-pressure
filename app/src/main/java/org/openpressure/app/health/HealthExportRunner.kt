package org.openpressure.app.health

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.openpressure.app.data.Reading

/** Keeps successful writes durable while leaving transient failures pending for retry. */
internal class HealthExportRunner<T>(
    private val authorized: suspend () -> Boolean,
    private val pending: () -> List<Reading>,
    private val current: (Reading) -> Boolean,
    private val prepare: (Reading) -> T,
    private val insert: suspend (T) -> Unit,
    private val exported: (Reading) -> Unit,
    private val skipped: (Reading, String) -> Unit,
) {
    suspend fun run(): Boolean {
        while (authorized()) {
            currentCoroutineContext().ensureActive()
            val batch = pending()
            if (batch.isEmpty()) return true
            for (reading in batch) {
                currentCoroutineContext().ensureActive()
                if (!authorized()) return false
                if (!current(reading)) continue
                try {
                    val record = prepare(reading)
                    // Preparing may touch storage; check again immediately before the provider call.
                    if (!authorized()) return false
                    if (!current(reading)) continue
                    insert(record)
                    exported(reading)
                } catch (_: RemovedReadingException) {
                    // A deleted row may have been replaced with another reading using the same ID.
                } catch (invalid: IllegalArgumentException) {
                    skipped(reading, invalid.message ?: "Invalid reading")
                }
            }
        }
        return false
    }
}

internal class RemovedReadingException : Exception()
