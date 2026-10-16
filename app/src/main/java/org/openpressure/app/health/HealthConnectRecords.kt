package org.openpressure.app.health

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import org.openpressure.app.data.Reading
import org.openpressure.protocol.HealthExport
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal object HealthConnectRecords {
    fun prepare(
        reading: Reading,
        bank: Int,
        persist: (String, String, Int) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): BloodPressureRecord {
        require(bank in 1..2 && reading.user == bank) { "Reading does not belong to your selected bank" }
        val clientId: String
        val time: Instant
        val offset: ZoneOffset
        val systolic: Double
        val diastolic: Double
        if (reading.healthTime != null) {
            clientId = requireNotNull(reading.healthClientId) { "Missing prepared record identity" }
            time = Instant.parse(reading.healthTime)
            offset = ZoneOffset.ofTotalSeconds(requireNotNull(reading.healthOffset) { "Missing prepared time zone" })
            systolic = reading.sysMmHg()
            diastolic = reading.diaMmHg()
        } else {
            val payload = HealthExport.prepare(reading.fingerprint, reading.user, bank, reading.time,
                reading.systolic, reading.diastolic, reading.unit, zone, now)
            clientId = payload.clientRecordId
            time = payload.time
            offset = payload.offset
            systolic = payload.systolicMmHg
            diastolic = payload.diastolicMmHg
            // Save before insertion: an ambiguous provider failure must retry with the same timestamp.
            if (!persist(clientId, time.toString(), offset.totalSeconds)) throw RemovedReadingException()
        }
        val metadata = if (reading.source == "manual") {
            Metadata.manualEntry(clientRecordId = clientId)
        } else {
            // connect-client 1.1.0 has no blood-pressure-monitor Device type.
            Metadata.activelyRecorded(
                device = Device(Device.TYPE_UNKNOWN,
                    manufacturer = if (reading.source == "wlp-history") "Omron" else null,
                    model = reading.name?.takeIf { it.isNotBlank() }),
                clientRecordId = clientId,
            )
        }
        return BloodPressureRecord(
            time = time, zoneOffset = offset, metadata = metadata,
            systolic = Pressure.millimetersOfMercury(systolic),
            diastolic = Pressure.millimetersOfMercury(diastolic),
        )
    }
}
