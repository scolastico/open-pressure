# Device reports

Only one monitor identity has been tested with OpenPressure. Device reports let owners of other monitors help without sharing health data. A report describes how the monitor communicated during one session, which is the evidence needed to confirm, fix or extend support for that model.

## When the app offers a report

After a **Pair** or **Connect** session with a monitor other than the tested HEM-7380T1-EOSL, the Devices screen shows **Help support this monitor**. After a Pair attempt, the app also asks once per monitor whether you want to review the report. After a [Sync clock](usage.md#sync-clock) that relied on the hidden developer option because clock sync is untested on that monitor (not the HEM-7380T1-EOSL, where it is confirmed) and actually sent the write (confirmed, rejected or interrupted; not when the clock was already within a minute or nothing was written), the app also asks once whether you want to review the report. The same applies after a Pair whose profile registration relied on the developer option because registration is untested on that monitor (HEM-7380T1-EBK) and sent a write; that question replaces the usual Pair question. Choosing **Not now** dismisses the question; the card stays available until the next connection replaces the report.

No report is offered for the tested monitor (its confirmed Sync clock and registration do not offer one, although a recorded write in `writes` makes its report available on the card), for automatic imports, or when the connection ended before the monitor's Bluetooth services were discovered. A report covers a failed session as well as a successful one; failures are often the most useful.

## What the report contains

| Included | Purpose |
| --- | --- |
| App version, Android version and phone model | Reproduce Android-specific behavior |
| Model, firmware and manufacturer from the monitor's Device Information | Identify the exact variant |
| Advertised name with the unit-specific part masked | Recognize the model family |
| Advertisement type, flags and length; all other bytes masked | Check [automatic-import support](automatic-import.md#eligible-monitors) |
| Bluetooth service and characteristic UUIDs with their properties, and the negotiated ATT MTU | Compare the layout with the [protocol reference](protocol.md) |
| Selected protocol path and memory map | See which implementation the app chose |
| First two bytes and length of each application-key acknowledgment | Check the legacy unlock exchange |
| Counts of decoded and rejected records, and whether the session end was confirmed | Check the memory map without values |
| For each attempted write, up to eight: which part of the monitor was written, a fixed result word and whether a developer override allowed an untested write ([details](safety.md#device-report-writes-section)) | Track write support without values |
| Names of the [experimental developer switches](development.md#experimental-paths) in effect for the session, under `developer` (for example `force_x7_encrypted`) | Tell experimental-path results apart |
| Final status and failure message, with addresses and long identifiers removed | Locate where the session stopped |

The report never contains readings, measurement times, profile or monitor-user names, the Bluetooth address, the serial number or system ID, memory contents, pairing keys or key bodies. Record sequence numbers and counts from the advertisement are masked. The [diagnostics export](usage.md) is different: it can contain health data and identifiers and should not be posted publicly.

Collecting a report sends nothing extra to the monitor. It records only what the session already did, so it follows the same [safety boundaries](safety.md): no probing of unknown memory and no guessed commands. Connect and imports write nothing to the monitor's memory, and Pair writes only the documented [X7 profile registration](safety.md#x7-profile-registration), recorded in the `writes` section as one `registration` entry per confirmed attempt with its outcome and whether the developer override allowed it. The only other memory write, an explicit Sync clock, is recorded as one `clock_block` entry in the same way; the written time, the selected user and the monitor's clock are never included. The `developer` list contains switch names only.

## Sharing it

1. Choose **Review device report** and read the report on screen.
2. Choose **Copy and open GitHub**. The app copies the report and opens the **Device report** issue form with the model filled in. Paste the report into the form, tick what worked and submit. A GitHub account is required.
3. Alternatively choose **Save file** and attach or paste the JSON later.

Nothing is uploaded automatically. The report leaves the phone only when you paste or attach it somewhere.

## What happens with a report

A report can confirm that a model matches an existing map, reveal a difference that blocks it, or provide the identity and layout needed to look for source evidence. A single report never enables a new memory map or write command by itself; changes still follow the [compatibility](compatibility.md) and [hardware validation](hardware-validation.md) rules. Confirmed results are recorded per model and operation.

Implementation: `org.openpressure.protocol.DeviceReport` builds and redacts the JSON (`open-pressure-device-report-v1`); `MonitorService` fills it during user-started sessions. Tests cover masking, scrubbed failure text, the tested-identity exclusion, the once-per-monitor prompt and a real Pair session through the service callbacks.
