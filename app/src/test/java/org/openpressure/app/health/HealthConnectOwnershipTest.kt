package org.openpressure.app.health

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openpressure.app.data.ReadingStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HealthConnectOwnershipTest {
    @Test fun `profile reassignment while preparing prevents provider insertion`() = verifyReassignment(false)

    @Test fun `profile reassignment while preparing a persisted retry prevents provider insertion`() = verifyReassignment(true)

    private fun verifyReassignment(retry: Boolean) = runBlocking {
        ReadingStore(RuntimeEnvironment.getApplication()).use { store ->
            val own = store.createVirtualUser("Alice")
            val other = store.createVirtualUser("Bob")
            store.setVirtualUsersEnabled(true)
            store.addManual(120.0, 80.0, 60.0, 2, LocalDateTime.of(2026, 10, 15, 10, 0), "")
            store.assignPending(store.nextPendingAssignment().id, own.id)
            val filter = -own.id
            if (retry) {
                val reading = store.healthPending(filter, 50).single()
                assertTrue(store.prepareHealthExport(reading.id, reading.fingerprint, "stable-retry", "2026-10-15T08:00:00Z", 7200))
            }
            var prepared = false
            val runner = HealthExportRunner(
                authorized = { true },
                pending = { store.healthPending(filter, 50) },
                current = { store.hasReadingIdentity(it.id, it.fingerprint, filter) },
                prepare = { reading ->
                    val record = HealthConnectRecords.prepare(reading, filter, { id, time, offset ->
                        store.prepareHealthExport(reading.id, reading.fingerprint, id, time, offset)
                    }, ZoneId.of("UTC"), Instant.parse("2026-10-16T12:00:00Z"))
                    prepared = true
                    // Deleting the selected profile transfers ownership without replacing the reading fingerprint.
                    store.deleteVirtualUser(own.id, other.id)
                    assertTrue(store.hasReadingIdentity(reading.id, reading.fingerprint))
                    record
                },
                insert = { fail("Ownership changed before provider insertion") },
                exported = { fail("Unwritten reading must stay pending") },
                skipped = { _, _ -> fail("Reassignment must not skip another person's record") },
            )
            assertTrue(runner.run())
            assertTrue(prepared)
            assertEquals(1, store.healthPending(-other.id, 50).size)
            assertEquals(0, store.healthExportedCount(-other.id))
        }
    }
}
