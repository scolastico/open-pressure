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

Pair immediately imports history on the same connection. Subsequent **Connect** leaves history available through **Import monitor history**. HEM-7377T1/BP5360 uses Android bonding without the legacy key exchange. The BP5360 uses in-record metadata to distinguish users. Reads may include empty or non-record slots; empty slots are skipped, damaged or non-record data is diagnosed and counted as rejected, and valid readings already received remain saved.

Most of these monitors keep a short list of how many readings each user has stored. **Import monitor history** reads that list first and then only the slots it names. If the list looks inconsistent or the monitor does not answer the request for it, the app reads every slot of the chosen users instead. HEM-7322T, HEM-7377T1 and HEM-7600T have no usable list, so they always read every slot.

The base HEM-7156T shares the HEM-7155T history layout. Scanning recognizes mixed-case names such as `BLESmart_…`; the app still verifies the actual model after connecting.

### Readings to import

Monitors with two users show **Readings to import** on the connected monitor and on each saved monitor: **User 1**, **User 2** or **Both users**. The choice is saved per monitor and applies to Pair, Import monitor history, Read all and automatic imports. The default is both users, and monitors saved before an update keep importing both; after X7 profile registration the choice changes to the registered user. On HEM-7377T1/BP5360 both users share the same memory, so every slot is still read and readings are kept or skipped by the user recorded in each reading. One-user models, including the HEM-7156T, show no choice. The choice only decides what OpenPressure copies to the phone; the monitor's memory is unchanged.

### Read all

**Read all**, below Import monitor history on these vendor-memory monitors, checks every memory slot of the chosen users regardless of what the monitor's list says. Use it if readings seem to be missing. It takes longer; keep the monitor nearby. Read all is only available while you are connected, never during an automatic import. On the HEM-7156T it also tries a second memory area that the monitor may not have. If the monitor does not answer for that area, the app skips it without failing the import.

After an import the summary explains how slots were chosen:

| Summary | Meaning |
| --- | --- |
| The monitor reported *N* stored readings for the chosen users. | The monitor's list was used; *N* slots were read for the chosen users. Empty slots among them are skipped. |
| The monitor reported no stored readings for the chosen users. Use Read all if you expect some. | The list was empty, so nothing was read. |
| The monitor's list of stored readings could not be used, so every memory slot was read. | The list looked inconsistent or could not be read; every slot of the chosen users was read instead. |
| Every memory slot was read. | Read all, or a model without a usable list. |
| *N* memory slots could not be read. | Read all, or a model without a usable list, reached the end of the transfer, but *N* slots held damaged data. The import still counts as complete and the other readings are saved. An automatic import with damaged slots is not marked as done, so it is tried again later. When the monitor's list was used, damaged slots make the import incomplete instead. |
| A second memory area this model may not have was skipped. | HEM-7156T only: Read all found no answer for its second area. It appears together with "Every memory slot was read." |

These import paths are built from source evidence and checked with synthetic tests only; they have not been tried on a physical monitor of these models.

An import only reads: once all history has arrived, the app ends the session without changing the monitor's clock or any other setting. To set the monitor's clock, use [Sync clock](#sync-clock) on a separate connection. If the connection closes or session finalization fails after all history arrives, the readings remain imported and the summary explicitly says END was not acknowledged. A disconnect earlier in the transfer remains an interrupted import.

After a successful Pair, these models can also use [automatic imports](automatic-import.md), which never change the monitor's clock. Raw EEPROM dumps and unread-counter changes are not enabled for these vendor paths. Dedicated standard-profile time/history controls remain available when that standard profile is selected.

## History and local data

Optional [Virtual users](virtual-users.md), on Overview, lets you create and rename named profiles instead of the usual User 1/User 2 choices. New readings are retained in an assignment queue until you choose who took them. Foreground use opens the oldest pending reading; a background notification opens the same screen. Ignoring it or choosing Later keeps all pending readings for your next visit. Existing history stays intact and is not guessed into named profiles.

Open **Measurement reminders** on Overview to create phone notifications at your chosen times and weekdays. Each reminder can skip an occurrence after a recent measurement; the default window is 120 minutes, and 0 disables skipping. With no weekdays selected, the reminder runs once. These settings do not change alarms on the cuff. See [measurement reminders](reminders.md) for permissions, timing and privacy details.

Optional [HTTP forwarding](http-forwarding.md) is available in **Devices → Advanced**. Configure a destination URL and optional Authorization header value to send new measurements. Local history remains enabled by default. Turning off **Keep new readings in local history** enables passthrough: new readings are queued privately until accepted by the server, without adding them to local history or Health Connect. Existing history stays. Clearing the destination disables forwarding and restores local storage for future readings.

**Import monitor history** imports stored records. X7 reads a fresh index and only the populated slots of the users chosen under **Readings to import**, leaving cuff history and unread counters intact. Local History filters select All users / User 1 / User 2 and date ranges. Manual entries, notes, trends and filtered CSV export are stored on the phone. The selected profile is shared by Overview and History and saved across app restarts; opening a notification preserves that selection.

Repeated imports deduplicate dated identical packets from the same device/user. X7 raw records also retain their sequence, supporting deduplication when time is unavailable. Undated live SIG packets remain separate rather than being merged by pressure values alone. Partial imports keep validated readings already saved; reconnect and repeat the import to finish.

Deleting one reading removes it and its note locally. **Clear local history**, below the readings and Show more, offers All users / User 1 / User 2, then a counted confirmation covering all dates, regardless of display filters. All users includes manual/unassigned readings. Other banks remain when one bank is selected. Saved monitors, pairing keys, settings and snapshots remain. Cuff memory and existing Health Connect copies are retained.

Local deletions made with deletion-receipt support record SHA-256 fingerprints. Automatic imports honor those receipts. A deliberate manual import can restore the same readings. Earlier deletions cannot be reconstructed into receipts.

## Time and flags

On the X7, Pair sets the clock as part of its user registration. Afterwards, and on the vendor-memory monitors, the clock control is [Sync clock](#sync-clock), once it is confirmed for your model; until then these monitors show no clock control. Supported standard profiles offer **Set monitor time**, an explicit clock control using a dedicated time characteristic; inspect or read back the monitor time after using it. A successful GATT write alone is not verification.

A rejected standard-profile clock update leaves history available. If that write times out without an Android callback, the app closes the connection and asks you to reconnect before importing, because Android may still consider the old write busy.

Cuff timestamps are local wall time without the original timezone. Missing or invalid times remain unavailable. Irregular pulse and AFib are different flags; an irregular-pulse flag never implies AFib. The separate X7 AFib field comes from its model map and still needs comparison against actual monitor results.

### Sync clock

**Sync clock** sets the monitor's clock to this phone's time. It appears on the connected monitor for the vendor-memory monitors above and for the X7 once clock sync is confirmed for that model; on an encrypted X7 connection the button stays disabled. You see both times and decide before anything on the monitor changes.

1. Choose **Connect** (not Pair) and wait until the monitor is ready. Use Sync clock before you import: it is not available during or after an import on the same connection, during Pair or during an automatic import.
2. Choose **Sync clock**. The app reads the monitor's current settings; nothing is changed yet.
3. The confirmation shows the monitor's clock (or that it is not set), the phone's time and the difference between them. Choose **Sync now** to continue or **Cancel** to stop. If you do not answer within 90 seconds, the sync is cancelled and nothing changes.
4. If the monitor's clock is already within a minute of the phone's time, it is left unchanged.
5. Otherwise the app first saves a copy of the monitor's settings on the phone, then sets the clock once. A failed attempt is never repeated automatically.
6. The connection ends afterwards. Connect again to import readings.

| Result | Meaning |
| --- | --- |
| Clock synced | The monitor confirmed the new time. Check that the monitor shows it. |
| Clock already correct | The monitor's clock was within a minute of the phone's time, so nothing was changed. |
| Clock sync failed | The monitor did not confirm the new time. Check the time on the monitor and set it there if needed. |
| Clock sync cancelled | You cancelled, did not answer in time or the connection ended. If the connection ended while the time was being set, the monitor's clock is uncertain; check it on the monitor. |
| Clock sync not possible | The monitor's settings could not be checked, or the phone's date is outside the supported range, so nothing was changed. |

Clock sync has not been tested on any physical monitor yet, so the **Sync clock** button is currently not shown on any model. On HEM-7322T and HEM-7377T1 it can never be used, because where they keep their clock is unknown. It is designed to change only the monitor's clock, never your readings, users or other settings.

## Snapshots and exports

### PDF reports

Choose **Export PDF** on Overview or History. The dialog shows the currently selected profile (All users, User 1 or User 2); change that selection before opening the dialog if needed. Choose **All time** or **Date range**, set the first and last dates, then choose **Export** and save the PDF using Android's file picker. Both dates are included. The PDF range is independent of the History screen's date filter and includes all matching stored records, including records beyond Show more.

The first page is an A4 portrait overview with counts, average blood pressure and pulse, and minimum/maximum values for each profile. All subsequent pages are A4 landscape. Charts show blood pressure and pulse separately, with up to 48 readings per page for one profile. Different profiles are never connected by one trend line. Detailed tables include date/time, profile, original pressure units, pulse, mean arterial pressure when available, monitor flags, the separate AFib field, device/source and notes. Tables repeat their headings and continue long notes across pages without truncation.

Charts and overview statistics use mmHg; tables retain the original units. Missing measurement times use the date received in the phone's time zone for range selection and plotting, are marked in the table and appear as hollow chart points. Monitor timestamps have no original time zone. Missing pulse or mean pressure is shown as a dash. No clinical diagnosis or pressure classification is added.

PDF generation runs locally and takes a snapshot before opening the save dialog. Cancelling the save dialog removes the temporary report. Exporting does not connect to or change the monitor.

### Monitor memory and diagnostics

Devices → **Advanced** offers **Read monitor memory** and **Export memory JSON** for X7. The default address `0010`, length 68, captures settings/index only. It is not a full EEPROM or calibration backup. Other bounded ranges may encounter rejected holes or unused slots; do not assume all addresses exist. Complete means the requested bytes and final session-end acknowledgment validated.

An interrupted read retains actual validated partial bytes with incomplete/error status. JSON includes the device/model, requested address/length, received count, scope, raw hex and SHA-256. A new ordinary dump replaces the previous local dump; export it first. Pre-registration settings backups and the settings copies saved before a clock sync are separate retained files; under Advanced, the newest of each kind has its own card with **Export settings backup**. Neither type enables an automatic memory restore.

Exports contain health data and identifiers. Review and redact diagnostics before sharing them. The separate [device report](device-reports.md) excludes readings and identifiers and is meant for public issues. Pairing bodies, keys and user consent exchanges are omitted from diagnostic export. Do not publish private traces or raw snapshots in the repository.

## Troubleshooting

- **Android companion approval keeps searching:** enable Bluetooth first. Confirm the cuff is already bonded, cancel the old dialog and retry approval. Android discovery may also need Location Services. The reported Pixel approval problem was resolved by enabling Bluetooth.
- **Cuff powers off after transfer:** after a validated END this is expected on the tested X7. The completed result remains saved. Wake and reconnect for another transfer.
- **Transfer stops or cuff shows Err:** retain diagnostics and the partial result, reconnect and retry a supported history/default-range read. Do not infer authentication failure from an undocumented device status or retry with guessed writes.
- **Another monitor is rejected or untested:** check [compatibility](compatibility.md). Other WLP maps are deliberately unavailable. Share the [device report](device-reports.md) offered on the Devices screen to help add support.
- **No background alert:** review notification permission and the New readings channel. Android restrictions and advertising timing can delay or prevent imports. See [automatic imports](automatic-import.md).
