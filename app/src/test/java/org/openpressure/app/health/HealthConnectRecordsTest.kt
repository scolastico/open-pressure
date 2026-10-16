package org.openpressure.app.health

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Test
import org.openpressure.app.data.Reading
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectRecordsTest {
    @Test fun `monitor write has original device and actively recorded attribution`() {
        val record = HealthConnectRecords.prepare(reading(), 1, { _, _, _ -> true }, ZoneId.of("Europe/Berlin"), Instant.parse("2026-10-16T12:00:00Z"))
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, record.metadata.recordingMethod)
        assertEquals("Omron", record.metadata.device?.manufacturer)
        assertEquals("X7 Smart AFib", record.metadata.device?.model)
        assertEquals(Device.TYPE_UNKNOWN, record.metadata.device?.type)
        assertEquals(120.0, record.systolic.inMillimetersOfMercury, 0.0)
        assertEquals(80.0, record.diastolic.inMillimetersOfMercury, 0.0)
    }

    @Test fun `manual entries are attributed to the user without an invented device`() {
        val reading = reading().apply { source = "manual"; name = "Manual entry" }
        val record = HealthConnectRecords.prepare(reading, 1, { _, _, _ -> true }, ZoneId.of("UTC"), Instant.parse("2026-10-16T12:00:00Z"))
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
        assertNull(record.metadata.device)
    }

    @Test fun `retry keeps persisted timestamp and client identity after phone zone changes`() {
        val reading = reading().apply {
            healthClientId = "existing-id"
            healthTime = "2026-10-15T08:00:00Z"
            healthOffset = 7200
        }
        val record = HealthConnectRecords.prepare(reading, 1, { _, _, _ -> fail("Must not replace prepared identity"); false }, ZoneId.of("America/New_York"), Instant.parse("2026-10-16T12:00:00Z"))
        assertEquals("existing-id", record.metadata.clientRecordId)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), record.time)
        assertEquals(ZoneOffset.ofHours(2), record.zoneOffset)
    }

    @Test fun `first export persists its identity and phone zone before returning a record`() {
        var savedId: String? = null
        var savedTime: String? = null
        var savedOffset: Int? = null
        val record = HealthConnectRecords.prepare(reading(), 1, { id, time, offset ->
            savedId = id; savedTime = time; savedOffset = offset; true
        }, ZoneId.of("Europe/Berlin"), Instant.parse("2026-10-16T12:00:00Z"))
        assertEquals(record.metadata.clientRecordId, savedId)
        assertEquals("2026-10-15T08:00:00Z", savedTime)
        assertEquals(7200, savedOffset)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), record.time)
    }

    @Test fun `generic SIG monitor does not invent its manufacturer`() {
        val reading = reading().apply { source = "sig"; name = "Generic Blood Pressure Monitor" }
        val record = HealthConnectRecords.prepare(reading, 1, { _, _, _ -> true }, ZoneId.of("UTC"), Instant.parse("2026-10-16T12:00:00Z"))
        assertNull(record.metadata.device?.manufacturer)
        assertEquals("Generic Blood Pressure Monitor", record.metadata.device?.model)
    }

    @Test(expected = RemovedReadingException::class)
    fun `failed identity reservation prevents creation of an exportable record`() {
        HealthConnectRecords.prepare(reading(), 1, { _, _, _ -> false }, ZoneId.of("UTC"), Instant.parse("2026-10-16T12:00:00Z"))
    }

    private fun reading() = Reading().apply {
        fingerprint = "test-fingerprint"; user = 1; time = "2026-10-15T10:00:00"
        systolic = 120.0; diastolic = 80.0; unit = "mmHg"
        name = "X7 Smart AFib"; source = "wlp-history"
    }
}
