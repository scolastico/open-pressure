package org.openpressure.app.forwarding

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.openpressure.app.PressureApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Durable wake-up; payloads and authorization remain in private app storage. */
class HttpForwardingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val completed = runInterruptible(Dispatchers.IO) {
            (applicationContext as PressureApp).httpForwarding().exportPending()
        }
        return if (completed) Result.success() else Result.retry()
    }
}
