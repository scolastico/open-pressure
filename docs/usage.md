# Using a monitor

## First use and permissions

OpenPressure requires Android 8/API 26 or newer. The first-use notice requires explicit agreement before Bluetooth service use. It explains that the app is experimental, has no guarantee of correct operation and accepts no responsibility for damaged or bricked devices. Close exits the app. Updating preserves saved readings, notes and accepted notice state.

Enable Bluetooth and grant Nearby devices permissions on Android 12+. Android 8–11 require location permission and Location Services for scans. Background imports have additional mode-specific limits described in [automatic imports](automatic-import.md).

## Pairing and connecting

In Devices, choose the model hint, put the cuff into the pairing mode described in its manual, scan and select it. **Pair** starts initial setup; **Connect** is the routine reconnect/read path. Selecting a model is a hint; the app still checks discovered capabilities and the actual model before X7 memory access.

For the tested plain-WLP HEM-7380T1-EOSL, Pair replaces an existing Android bond and waits for fresh confirmation. If Android blocks removal, forget this cuff in Android Bluetooth settings and retry. After transport verification, the app reads and saves a settings/index backup. Choose user 1 or 2 **on the phone**, check the exact cuff label and confirm registration. The intended changes are the selected-user setting and phone clock. The backup and narrow write bounds are documented in [safety](safety.md#x7-profile-registration).

The tested plain-X7 workflow does not require inventing a physical profile-button step. Follow actual cuff/Android prompts. A Bluetooth bond or successful transport check alone does not mean profile registration has completed.

Encrypted X7 uses a separate application pairing and cuff-authorization path. Pair starts fresh application pairing; Connect uses its saved key when available. Read controls stay disabled until explicit authorization completes. If reconnecting requires security metadata, the app scans the saved address and resumes once recognized. This encrypted path remains untested on physical hardware.

Monitors exposing User Data Service may offer **Authenticate / register user**. Choose the matching bank; the recovered default consent code is `526`. Register for initial user setup during cuff pairing, and authenticate during routine connection. Standard-profile registration is separate from the X7 workflow and remains hardware-untested.

## Other vendor-memory monitors

The [compatibility table](compatibility.md) lists the additional implemented models. The app reads the actual Device Information model and validates its channel layout; a UI model hint cannot override an unknown map. These readers are not hardware-tested in OpenPressure.

On older legacy models, put the monitor into pairing mode and choose **Pair**. Confirm Android's Bluetooth prompt. The app then registers a random application key through the device's dedicated control characteristic. This can replace the key used by another client; switching back may require that client's pairing procedure. Subsequent **Connect** uses the saved key and never starts key programming. If pairing is interrupted, retrying Pair reuses its recoverable pending key.

HEM-7377T1/BP5360 uses Android bonding without the legacy key exchange. After connection, choose **Import monitor history** to read the mapped banks. The BP5360 uses in-record metadata to distinguish users. Full-bank reads may include empty or non-record slots; rejected data is diagnosed, valid readings already received remain saved, and the session must acknowledge END before success.

These added vendor paths offer manual history imports. Automatic imports, raw EEPROM dumps and EEPROM-based clock/counter changes are not enabled for them. Dedicated standard-profile time/history controls remain available when that standard profile is selected.

## History and local data

**Import monitor history** imports stored records. X7 reads a fresh index and only populated slots in both banks, leaving cuff history and unread counters intact. Local History filters select All users / User 1 / User 2 and date ranges. Manual entries, notes, trends and filtered CSV export are stored on the phone. The selected profile is shared by Overview and History and saved across app restarts; opening a notification preserves that selection.

Repeated imports deduplicate dated identical packets from the same device/user. X7 raw records also retain their sequence, supporting deduplication when time is unavailable. Undated live SIG packets remain separate rather than being merged by pressure values alone. Partial imports keep validated readings already saved; reconnect and repeat the import to finish.

Deleting one reading removes it and its note locally. **Clear local history**, below the readings and Show more, offers All users / User 1 / User 2, then a counted confirmation covering all dates, regardless of display filters. All users includes manual/unassigned readings. Other banks remain when one bank is selected. Saved monitors, pairing keys, settings and snapshots remain. Cuff memory and existing Health Connect copies are retained.

Local deletions made with deletion-receipt support record SHA-256 fingerprints. Automatic imports honor those receipts. A deliberate manual import can restore the same readings. Earlier deletions cannot be reconstructed into receipts.

## Time and flags

X7 registration updates the selected-user setting and clock. There is no standalone X7 clock-write control. Supported standard profiles offer an explicit clock control using a dedicated time characteristic; inspect or read back the monitor time after using it. A successful GATT write alone is not verification.

Cuff timestamps are local wall time without the original timezone. Missing or invalid times remain unavailable. Irregular pulse and AFib are different flags; an irregular-pulse flag never implies AFib. The separate X7 AFib field comes from its model map and still needs comparison against actual monitor results.

## Snapshots and exports

### PDF reports

Choose **Export PDF** on Overview or History. The dialog shows the currently selected profile (All users, User 1 or User 2); change that selection before opening the dialog if needed. Choose **All time** or **Date range**, set the first and last dates, then choose **Export** and save the PDF using Android's file picker. Both dates are included. The PDF range is independent of the History screen's date filter and includes all matching stored records, including records beyond Show more.

The first page is an A4 portrait overview with counts, average blood pressure and pulse, and minimum/maximum values for each profile. All subsequent pages are A4 landscape. Charts show blood pressure and pulse separately, with up to 48 readings per page for one profile. Different profiles are never connected by one trend line. Detailed tables include date/time, profile, original pressure units, pulse, mean arterial pressure when available, monitor flags, the separate AFib field, device/source and notes. Tables repeat their headings and continue long notes across pages without truncation.

Charts and overview statistics use mmHg; tables retain the original units. Missing measurement times use the date received in the phone's time zone for range selection and plotting, are marked in the table and appear as hollow chart points. Monitor timestamps have no original time zone. Missing pulse or mean pressure is shown as a dash. No clinical diagnosis or pressure classification is added.

PDF generation runs locally and takes a snapshot before opening the save dialog. Cancelling the save dialog removes the temporary report. Exporting does not connect to or change the monitor.

### Monitor memory and diagnostics

Devices → **Advanced** offers **Read monitor memory** and **Export memory JSON** for X7. The default address `0010`, length 68, captures settings/index only. It is not a full EEPROM or calibration backup. Other bounded ranges may encounter rejected holes or unused slots; do not assume all addresses exist. Complete means the requested bytes and final session-end acknowledgment validated.

An interrupted read retains actual validated partial bytes with incomplete/error status. JSON includes the device/model, requested address/length, received count, scope, raw hex and SHA-256. A new ordinary dump replaces the previous local dump; export it first. Pre-registration settings backups are separate retained files. Neither type enables an automatic memory restore.

Exports contain health data and identifiers. Review and redact diagnostics before sharing them. Pairing bodies, keys and user consent exchanges are omitted from diagnostic export. Do not publish private traces or raw snapshots in the repository.

## Troubleshooting

- **Android companion approval keeps searching:** enable Bluetooth first. Confirm the cuff is already bonded, cancel the old dialog and retry approval. Android discovery may also need Location Services. The reported Pixel approval problem was resolved by enabling Bluetooth.
- **Cuff powers off after transfer:** after a validated END this is expected on the tested X7. The completed result remains saved. Wake and reconnect for another transfer.
- **Transfer stops or cuff shows Err:** retain diagnostics and the partial result, reconnect and retry a supported history/default-range read. Do not infer authentication failure from an undocumented device status or retry with guessed writes.
- **Another monitor is rejected:** check [compatibility](compatibility.md). Other WLP maps are deliberately unavailable.
- **No background alert:** review notification permission and the New readings channel. Android restrictions and advertising timing can delay or prevent imports. See [automatic imports](automatic-import.md).
