package org.openpressure.app.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openpressure.app.data.Reading
import java.io.IOException

class HealthExportRunnerTest {
    @Test fun `failed insert leaves local record pending`() = runBlocking {
        val pending = mutableListOf(reading(1))
        val exported = mutableListOf<Long>()
        val runner = HealthExportRunner(
            authorized = { true }, pending = { pending.toList() }, current = { true },
            prepare = { it.id }, insert = { throw IOException("Provider busy") },
            exported = { exported.add(it.id) }, skipped = { _, _ -> fail("Unexpected skip") }
        )
        try { runner.run(); fail("Expected transient failure") } catch (_: IOException) { }
        assertTrue(exported.isEmpty())
        assertEquals(1, pending.size)
    }

    @Test fun `revocation stops before next export and only successful item is marked`() = runBlocking {
        val pending = mutableListOf(reading(1), reading(2))
        val exported = mutableListOf<Long>()
        var granted = true
        val runner = HealthExportRunner(
            authorized = { granted }, pending = { pending.toList() }, current = { true },
            prepare = { it.id }, insert = { granted = false },
            exported = { reading -> exported.add(reading.id); pending.removeAll { it.id == reading.id } },
            skipped = { _, _ -> fail("Unexpected skip") }
        )
        assertFalse(runner.run())
        assertEquals(listOf(1L), exported)
        assertEquals(listOf(2L), pending.map { it.id })
    }

    @Test fun `one invalid item does not block later valid readings`() = runBlocking {
        val pending = mutableListOf(reading(1), reading(2))
        val exported = mutableListOf<Long>()
        val skipped = mutableListOf<Long>()
        val runner = HealthExportRunner(
            authorized = { true }, pending = { pending.toList() }, current = { true },
            prepare = { if (it.id == 1L) throw IllegalArgumentException("Invalid date"); it.id },
            insert = { },
            exported = { reading -> exported.add(reading.id); pending.removeAll { it.id == reading.id } },
            skipped = { reading, _ -> skipped.add(reading.id); pending.removeAll { it.id == reading.id } }
        )
        assertTrue(runner.run())
        assertEquals(listOf(1L), skipped)
        assertEquals(listOf(2L), exported)
    }

    @Test fun `provider rejection skips only the rejected record`() = runBlocking {
        val pending = mutableListOf(reading(1), reading(2))
        val exported = mutableListOf<Long>()
        val skipped = mutableMapOf<Long, String>()
        val runner = HealthExportRunner(
            authorized = { true }, pending = { pending.toList() }, current = { true }, prepare = { it.id },
            insert = { if (it == 1L) throw IllegalArgumentException("systolic exceeds provider range") },
            exported = { reading -> exported.add(reading.id); pending.removeAll { it.id == reading.id } },
            skipped = { reading, reason -> skipped[reading.id] = reason; pending.removeAll { it.id == reading.id } }
        )
        assertTrue(runner.run())
        assertEquals(mapOf(1L to "systolic exceeds provider range"), skipped)
        assertEquals(listOf(2L), exported)
    }

    @Test fun `revocation while preparing prevents the provider write`() = runBlocking {
        var granted = true
        val runner = HealthExportRunner(
            authorized = { granted }, pending = { listOf(reading(1)) }, current = { true },
            prepare = { granted = false; it.id },
            insert = { fail("Permission was revoked before insertion") },
            exported = { fail("An unwritten record must remain pending") },
            skipped = { _, _ -> fail("An ungranted record must remain pending") }
        )
        assertFalse(runner.run())
    }

    @Test fun `reused row id after a permission wait exports the replacement independently`() = runBlocking {
        val original = reading(1).apply { fingerprint = "original" }
        val replacement = reading(1).apply { fingerprint = "replacement" }
        val pending = mutableListOf(original)
        val inserted = mutableListOf<String>()
        var permissionChecks = 0
        val runner = HealthExportRunner(
            authorized = {
                permissionChecks++
                if (permissionChecks == 2) { pending.clear(); pending.add(replacement) }
                true
            },
            pending = { pending.toList() },
            current = { reading -> pending.any { it.id == reading.id && it.fingerprint == reading.fingerprint } },
            prepare = { it.fingerprint },
            insert = { inserted.add(it) },
            exported = { reading -> pending.removeAll { it.id == reading.id && it.fingerprint == reading.fingerprint } },
            skipped = { _, _ -> fail("Unexpected skip") }
        )
        assertTrue(runner.run())
        assertEquals(listOf("replacement"), inserted)
        assertTrue(pending.isEmpty())
    }

    @Test fun `completion after an in-flight deletion cannot mark a replacement exported`() = runBlocking {
        val original = reading(1).apply { fingerprint = "original" }
        val replacement = reading(1).apply { fingerprint = "replacement" }
        val pending = mutableListOf(original)
        val inserted = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val runner = HealthExportRunner(
            authorized = { true }, pending = { pending.toList() },
            current = { reading -> pending.any { it.id == reading.id && it.fingerprint == reading.fingerprint } },
            prepare = { it.fingerprint },
            insert = { fingerprint ->
                inserted.add(fingerprint)
                if (fingerprint == "original") { pending.clear(); pending.add(replacement) }
            },
            exported = { reading ->
                if (pending.removeAll { it.id == reading.id && it.fingerprint == reading.fingerprint }) completed.add(reading.fingerprint)
            },
            skipped = { _, _ -> fail("Unexpected skip") },
        )
        assertTrue(runner.run())
        assertEquals(listOf("original", "replacement"), inserted)
        assertEquals(listOf("replacement"), completed)
    }

    @Test fun `failed identity reservation does not skip the replacement`() = runBlocking {
        val original = reading(1).apply { fingerprint = "original" }
        val replacement = reading(1).apply { fingerprint = "replacement" }
        val pending = mutableListOf(original)
        val inserted = mutableListOf<String>()
        val runner = HealthExportRunner(
            authorized = { true }, pending = { pending.toList() },
            current = { reading -> pending.any { it.id == reading.id && it.fingerprint == reading.fingerprint } },
            prepare = { reading ->
                if (reading.fingerprint == "original") {
                    pending.clear(); pending.add(replacement)
                    throw RemovedReadingException()
                }
                reading.fingerprint
            },
            insert = { inserted.add(it) },
            exported = { reading -> pending.removeAll { it.id == reading.id && it.fingerprint == reading.fingerprint } },
            skipped = { _, _ -> fail("A removed reading must not poison a replacement") },
        )
        assertTrue(runner.run())
        assertEquals(listOf("replacement"), inserted)
    }

    private fun reading(id: Long) = Reading().apply { this.id = id }
}
