# Optional Health Connect export

Export is **off by default**. Open Health Connect from Overview, choose your own user bank (1 or 2), enable automatic export and grant blood pressure write permission. The choice applies to that bank across all monitors. There is no all-users export because Health Connect represents one person's record. Experimental-use acceptance is also required.

Health Connect needs Android 9/API 28+ and an available provider; Android 9–13 use the installed provider app and Android 14+ use the system service. OpenPressure still runs locally on Android 8. Availability/install/manage controls explain provider status. See Android's [Health Connect setup guide](https://developer.android.com/health-and-fitness/health-connect/get-started).

## Data and privacy

Only `android.permission.health.WRITE_BLOOD_PRESSURE` is requested for Health Connect. OpenPressure does not request health-record read permission, read existing Health Connect records, or delete provider copies. The separate, off-by-default [HTTP forwarding](http-forwarding.md) feature uses network permission to reach a user-configured server; it is independent of Health Connect. The privacy screen is available without enabling export or Bluetooth.

Exports contain systolic/diastolic pressure, measurement time/zone offset, source metadata and an opaque client record ID. kPa values convert to mmHg. Manual entries use manual-entry attribution; cuff records use actively-recorded attribution. Device type is SDK unknown rather than an invented category. Pulse, AFib flags, notes, raw packets, EEPROM data, guest/unknown users and unselected banks are excluded.

Other apps may access or transfer Health Connect copies if separately authorized; their policies apply. Disabling export, deleting local readings or uninstalling OpenPressure retains existing provider copies. Manage those records and permissions in Health Connect.

Passthrough HTTP forwarding does not insert new local history rows, so those measurements do not enter Health Connect. Previously stored eligible readings can still export when Health Connect remains enabled. Keeping local history and enabling both features sends eligible new readings through their independent export paths.

## Timestamp and value validation

Cuff times are local wall time without the original timezone. At first export, the phone's current timezone determines the instant and offset; both are stored before writing and reused on retries even after a timezone change. A daylight-saving gap is rejected; an ambiguous overlap uses the earlier valid offset. This does not establish the cuff's actual historical timezone.

Missing, invalid or future timestamps and invalid pressure values are skipped with a reason, keeping the local reading. Receipt time is never substituted for absent measurement time. Local validation accepts finite positive values up to 300/200 mmHg with systolic greater than diastolic. The provider may apply stricter rules. These are data-validation limits, not clinical classifications. **Export readings now** also retries previously skipped readings.

## Reliability and controls

A persistent reading fingerprint hashes into a stable `clientRecordId`. The local outbox records prepared time/offset and marks success only after provider acknowledgment. Both preparation and completion recheck the immutable fingerprint, so deleting a reading and reusing its numeric SQLite ID cannot mark another reading exported. Repeated uncertain inserts keep the same identity, following the SDK's [duplicate/update behavior](https://developer.android.com/health-and-fitness/health-connect/write-data).

Enabling export queues eligible existing readings and new manual/Bluetooth readings. WorkManager persists/coalesces requests and schedules retry checks every 15 minutes; transient errors back off. Permission and selected bank are checked for each record and immediately before insertion. Invalid records do not block later valid records; lost permission pauses export.

Disabling cancels queued work and subsequent inserts; an already-dispatched provider call may finish. Re-enabling does not clear successful markers. Export counts represent locally acknowledged inserts, not a fresh inventory of provider data. Already-successful entries deliberately removed in Health Connect are not automatically recreated.

Android 14+ requests native health permissions and checks grants afterward; Android 9–13 use the SDK provider intent. The manifest supplies provider visibility plus the rationale/permission-usage entry points. A plain Activity cannot directly launch the SDK's synthetic Android 14 contract intent without an ActivityResultRegistry; the implementation uses the supported native path.

## Validation scope

Automated checks cover attribution, bank isolation, kPa conversion, missing/future timestamps, DST, persistent retry identity, rejected records, revoked permission and database migrations. Provider UI and actual device writes still need phone validation. Check initial-off behavior, both permission outcomes, exact pressure/time/bank, duplicate-free repeated export, revoked permission, queue cancellation and retained Health Connect copies. See [hardware validation](hardware-validation.md#health-connect).
