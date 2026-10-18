# Hardware validation

## Confirmed evidence

The tested monitor is **Omron X7 Smart AFib HEM-7380T1-EOSL**, plain WLP manufacturer type 6. The user reports version 0.1.13 working. Detailed supplied evidence establishes:

- Correct FE4A WLP channel layout and exact `X7 Smart AFib` DIS alias.
- Valid padded SYS nonce reply; the incoming response can be 244 bytes while the request remains exactly 20.
- Complete default settings/index read: 68 bytes at `[0x0010,0x0054)`, followed by validated END, then expected cuff shutdown about 3.2 seconds later.
- Both bounded registration-write echoes and END, then a separate complete 111/111-record history transfer. The user confirmed pairing, correct clock and a newly taken measurement imported manually with 0.1.10.
- Android companion approval on Pixel 9 Pro, Android 17 build `CP3A.260905.009`, after Bluetooth was enabled. The reported endless search was caused by Bluetooth being off.
- A standalone Sync clock (clock-block write) on 2026-10-18 with firmware SI.W03.00.03 and app 1.2.1: acknowledged, and the written time confirmed on the cuff ([run](#confirmed-run-x7-smart-afib-hem-7380t1-eosl)).

These are user-supplied results; no monitor or real Android device is attached to the development environment. They do not establish every automatic/background timing path, Health Connect provider flow, translation or new UI behavior. The 0.3.0 added vendor readers do not introduce a new hardware-verification claim. Encrypted X7, STP and other standard-profile monitor families remain hardware-untested.

The original supplied health traces are not part of the repository. Protocol facts and source hashes are recorded in the [reference](protocol.md), and current [compatibility](compatibility.md) applies per operation and model.

## Manual connection and history

Record the exact cuff label, firmware string, Android version, installed app version and selected user. Export anything you need to keep before changing pairing. Close competing Bluetooth clients.

1. Verify that first-use acceptance cannot be bypassed, Close prevents Bluetooth actions and acceptance survives restart. Updating must retain readings and notes.
2. Enable Bluetooth, grant the applicable permissions, scan and select the exact monitor. Confirm scan rows remain stable while advertising updates arrive.
3. Use Pair for an intentional fresh setup. On plain EOSL X7, check new Android bonding, settings backup, **phone** user choice and explicit registration confirmation. Cancel before confirmation and confirm no addressed writes occurred. Test routine Connect separately; it must reuse the bond and keep the read-only path.
4. After registration, wake/reconnect and import history. Verify the fresh index precedes only populated bank ranges and a final END. Compare pressure, pulse, date and bank against retained cuff/reference records. Expected counts come from the current index, not the historic 111-record example.
5. Repeat import to verify deduplication, then take a new measurement and import it. Different timestamps with identical values must remain distinct. Compare movement, irregular pulse and AFib separately; irregular pulse must not imply AFib.
6. Test an empty bank and both empty banks when practical. Missing/invalid timestamps must remain unavailable; an invalid declared slot must make completeness fail.
7. Interrupt a transfer by turning off Bluetooth or the cuff. Already validated readings must remain saved, and the result must say incomplete/interrupted. Wake/reconnect and retry.
8. Read/export the default 68-byte range. Check address, scope, byte count, raw hex, SHA-256 and END-dependent completion. Cancel a read and verify an incomplete export remains available. A normal post-END shutdown must preserve completion and require reconnecting for another transfer.
9. Rotate/background during connection and document pickers. CSV must honor current filters and preserve original units, nullable flags and notes; JSON must remain a stable snapshot.

For registration implementation changes, verify exact write bounds and failure handling from [safety](safety.md#x7-profile-registration). Storage failure must block writes; corruption, cancellation and disconnect must revoke permits. Do not force a real interrupted write merely for routine release testing. Software negative-path checks cover these cases without intentionally disturbing a working cuff.

## Added vendor-memory paths

For each exact model, compare DIS identity, service UUID and all RX/TX properties against [the map](omblepy-comparison.md). A [device report](device-reports.md) from the session records these, the key-acknowledgment shapes and the record counts without health values. Verify first-RX subscription and Android bonding, explicit Pair key provisioning only on legacy models, and saved-key-only Connect. Record the exact key-acknowledgment length in diagnostics. OpenPressure accepts one-byte and padded acknowledgments (first byte `8x`, a `00` second byte when present); that acceptance is covered only by synthetic tests. HEM-7377T1 must use the single-channel OS-bond path, without legacy key commands. A device exposing both a complete standard profile and vendor channels should retain the standard path.

Compare every decoded pressure, pulse, date and user with the monitor. On a four-channel legacy model, check that 56-byte reads (the 68-byte index in 56 + 12, then 48-byte record reads on 16-byte maps or 56-byte reads on 14-byte maps) complete without a device error, timeout or shortened reply; none has been observed on hardware yet.

On an index-supported model (HEM-7150T, 7155T, 7156T, 7342T, 7361T, 7530T, 6232T), run **Import monitor history**, then reconnect and run **Read all** without taking a new measurement. Read all should then add no new readings from the chosen users' banks, because the index-driven import already found them all; on HEM-7156T, readings from its second area are the exception described in the HEM-7156T paragraph below. The first summary should report the monitor's stored-reading count rather than the fallback message. Record the reported count against the readings shown on the monitor, including a full bank that has wrapped and an empty bank when practical. A fallback message on an unmodified monitor means the index rule does not match that firmware; keep the diagnostics. On a two-user model, check **Readings to import** for User 1, User 2 and both. Record checksums are now enforced on these maps and on HEM-7600T, so any rejected slot on a valid monitor reading is a finding to report. Exercise 14-byte bank boundaries, erased slots, an unset clock, repeat imports and a naturally interrupted transfer. On BP5360 verify both user profiles from the mixed second bank, and inspect rejected summaries. Confirm that imports send no `01 C0` addressed write, no Current Time or Date Time write and no unread reset. Do not intentionally interrupt key programming on hardware solely for release testing; synthetic tests cover lost ACK and retry recovery.

For HEM-7156T, verify discovery when only a mixed-case `BLESmart_…` name is advertised. Pair should proceed to user 1's history on the same connection. Then use **Read all** and record how the monitor answers for the second bank at `0x458`: records, erased slots, an error status or no reply. Omron's configuration does not list that bank, and its real behavior is unknown; the app should skip it without failing. The import must send no clock write before its only END. A disconnect during finalization must retain history and report the missing END acknowledgment; a disconnect before all history arrives must remain an interrupted transfer.

## Automatic imports and phone controls

1. With visible-app listening enabled, take a measurement and allow the cuff's normal transfer advertisement. A read-only history import should save the new reading without pressing Import. Repeated identical advertisements must remain idle after success.
   On a vendor-memory model, first record the manufacturer data shown during Scan: an automatic import requires type 1, 2 or 6. Verify that the import uses only the saved-key unlock, follows **Readings to import**, reads the index first on index-supported models, sends no clock write, and that the link closes after END.
2. Delete a reading locally, take another measurement and import automatically. The deleted reading must stay deleted. A deliberate manual import may restore it.
3. Test notification mode after leaving the app. On Android 13+, exercise allow/deny/re-enable notification permission and blocked New readings channel settings. One saved batch should produce one count-only alert opening History; duplicate/zero-insert attempts must not alert.
4. Test companion approval with Bluetooth off: decline the enable prompt, retry and allow it; rotate while the prompt is open. Approval must resume only for the still-saved target and selected mode. Then check background measurement arrival and expected cuff shutdown without a persistent companion-mode notification.
5. Exercise Bluetooth off/on, lost permissions, app restart, mode Off and manual Scan/Connect during automatic work. Unchanged saved revisions must remain idle across restart; failures must respect retry cooldowns. Record Android power-management restrictions when imports are delayed.
6. In History, cancel clearing, clear one bank and verify the other remains, then test All users on disposable local data. Counted confirmation spans all dates, including hidden filters. Cuff memory, saved monitors and Health Connect copies must remain. Automatic imports honor deletion receipts.
7. Pull down on Overview right after a measurement with automatic imports Off, then with two saved monitors advertising. The spinner must stay until each advertising monitor has been imported, one at a time, through the read-only path, and then clear; the mode must stay Off. With no monitor advertising, the fetch must end after about 15 seconds with the "No monitor is nearby" message. Disconnect or Scan during a fetch must stop it without importing the remaining monitors.
8. Check saved-profile selection and user filters across rotation/restart, including manual/unassigned records. Review localized dialogs, plural counts, long text and RTL layouts in representative languages.

A result for companion **approval** alone is not evidence of background transfer timing. Record each outcome separately.

## Health Connect

Use a phone/provider with disposable or backed-up test records. Verify initial-off state, explicit bank selection, granted/denied/revoked permission, pressure/time/count, repeated export without duplicates, new imports while UI is closed and disabling a pending queue. Excluded banks, pulse, AFib, notes and raw data must remain absent. Local deletion/disable must retain existing provider copies. Test Android 9–13 provider permission flow and Android 14+ native flow when devices are available.

## HTTP forwarding

Use a receiver and synthetic/disposable measurements under your control. Verify the default is off and ordinary local imports are unchanged. Configure HTTPS and the required Authorization value, then compare user bank, original time, units, pressure, pulse and flags with the received JSON. Verify credentials, raw packets and notes are absent from payloads and diagnostics.

Test local-history-on and passthrough separately. In passthrough, verify no new history, Health Connect rows or packet diagnostics are retained, while existing history and required safety backups stay intact. Lose network connectivity naturally, restart the app, restore connectivity and confirm queued delivery with the same idempotency key and cleanup only after a 2xx response. Confirm rejected authorization and redirects retain the queue. Change the URL and ensure old queued records are not rerouted; restore it to resume. Test clearing the URL, updating authorization, background scheduling and English/German/Arabic screens with large fonts. Do not send private health fixtures to a public test service.

## Measurement reminders

Use disposable measurements and phone-only schedules; no monitor commands are required. Create, edit, enable, disable and delete several reminders, including different weekday combinations and a one-time reminder with no weekdays. Check 12/24-hour time, persisted drafts after rotation, larger fonts and English/German/Arabic layouts.

Test notification permission allowed, denied and revoked, and a blocked Measurement reminders channel. Test exact-alarm access granted and denied on Android 12+, revoke it and reopen the app, then compare scheduled and actual notification times with the screen off and under the phone's power-management settings. Check normal swipe dismissal and tapping to open the app. Do not infer precise delivery from simulated alarms.

With a 120-minute suppression window, accept a recent measurement, import an old measurement, and test 0 to disable skipping. Verify one skipped occurrence leaves a repeating reminder active. Repeat in HTTP passthrough after the queued payload has been acknowledged; no new local history or pressure-value metadata should appear. Measurements from either bank can qualify. Test a late-delivered alert with a measurement after its scheduled time.

Reboot, update the app, change the clock/timezone and test daylight saving transitions on a dedicated test device. Confirm future reminders recover, missed powered-off occurrences are not replayed, stale/deleted schedules do not notify and repeated local times do not produce duplicate alerts. Record phone/Android version and actual delivery observations separately from automated results.

## Virtual users

Use synthetic/disposable measurements. Start with the feature off and confirm the existing monitor-bank flow. Enable it after creating a named user; add at least three users, rename them and remove one only after choosing a replacement. Verify that the last user cannot be removed and no measurement disappears. Check long names, larger fonts and RTL layout, especially systolic/diastolic order.

Take/import new measurements with the app foregrounded on different screens, then backgrounded. Confirm foreground assignment asks who measured; background notifications show a pending count and open the same picker directly. Ignore/swipe notifications, deny notification permission, take several measurements and restart the app/process. The queue must survive and process oldest first, with no implicit selection. Later/back must preserve the queue without immediately reopening it in the same foreground session. Repeated imports must not prompt again for the same stored record.

Check named-person filters, trends, CSV/PDF and counted deletion independently of physical banks. Registration controls must still use valid monitor memory banks, never virtual IDs. Existing history must stay accessible as Unassigned. Test switching the mode off with pending work, then back on. In HTTP passthrough, no history or Health Connect row should appear; assignment must precede an upload and its original destination must remain bound after settings changes.

With disposable Health Connect data, confirm mode changes invalidate old export consent, only the explicitly selected named person exports, pending measurements never export and deleting/replacing a profile cannot authorize a different person through an old selection. Server/provider copies remain after local renames/removal. Record physical-phone lifecycle, notification, translation and provider observations separately from simulated tests.

## Device reports and update checks

Pair an untested monitor and let the session end. The report question must appear once, after the monitor disconnects, and never for the EOSL cuff after Pair or during automatic imports. Review the report text in a long-text and an RTL language: it must stay left-to-right and contain no address, serial number, reading or timestamp. **Copy and open GitHub** must open the Device report form with the model filled in, and the clipboard must hold the report. **Save file** must write the same JSON.

With update checks on and an older installed version, the update dialog must appear when the app opens once the newer release is known. **Remind me later** and dismissing must suppress it for three days; **Skip this version** only for that version. Turning checks off under Devices → Advanced must stop all requests; verify with a network log if available.

## Confirming clock sync

The clock-block entry of HEM-7380T1-EOSL is confirmed (see the run below); every other listed entry is untested, so a clock-sync run there is a deliberate validation with a monitor you are prepared to lose. Never try it on a blocked model, and never repeat a failed write in the hope that it works. The [safety rules](safety.md#clock-block-write) describe what is and is not written.

1. Note the exact cuff label and firmware string. Import the monitor's history beforehand on a separate connection so the readings are on the phone, and note the stored-reading count and the user settings shown on the monitor.
2. Unlock the [developer options](development.md#developer-options) and turn on **Allow clock sync on monitors where it is untested**.
3. Make the result visible on the monitor: set its clock wrong on the monitor itself (for example an hour or a day off), or keep it and enter a deliberately wrong time in **Time to set (developer option)**. The difference must exceed one minute, or nothing is written.
4. **Connect** (not Pair) and choose **Sync clock**. Compare the monitor time in the confirmation with the monitor's own display; a mismatch means the stored clock was decoded wrongly, so cancel and report it. Otherwise confirm.
5. Record the result title. On the monitor, check that it shows the written time (the target plus about a second), that the date is right, and that users, settings and stored readings are unchanged. Where practical, repeat with a time just before midnight and check the date change.
6. Reconnect and import history: no reading may be lost, altered or duplicated. Take a new measurement and check that its timestamp follows the new clock.
7. Run Sync clock again with the phone time to restore the correct clock. When finished, turn the switch off or hide the developer options.
8. Send the device report the app offers after the write (its `writes` entry names `clock_block`, the outcome and `override: true`). Include the cuff label, firmware, Android and app versions, which time was written and what the monitor displayed. If a maintainer asks for the settings image, export the backup through Advanced → Export settings backup (the **Clock sync backup** card) and share it privately; keep diagnostics and that backup out of public issues, because they contain identifiers.

An acknowledged write that the monitor does not display is an important finding, not a reason to retry. OpenPressure does not send the index-prefix write that Omron Connect sends before the clock block ([protocol](protocol.md#clock-block-write-frame)), and whether monitors need it is unverified. A rejected or cancelled write leaves the monitor's state unverified: check the clock and settings on the monitor, report the result and stop testing that model until a maintainer has reviewed it.

A maintainer then follows [promoting a write aspect](#promoting-a-write-aspect) for that exact identity. Once its clock-block entry is confirmed, Sync clock works there without the developer option, and the maintainer updates [compatibility](compatibility.md), the [usage](usage.md#sync-clock) note on where Sync clock appears and this guide.

### Confirmed run: X7 Smart AFib HEM-7380T1-EOSL

On 2026-10-18 the maintainer ran Sync clock on an Omron X7 Smart AFib with cuff label HEM-7380T1-EOSL and firmware SI.W03.00.03, using app version 1.2.1 (a local signed build) on a Google Pixel 9 Pro with Android API 37. It was a manual **Connect** session over the plain WLP connection (verified model, ATT MTU 247) with a deliberately chosen wrong time in **Time to set (developer option)**. The device report recorded one `clock_block` write with outcome `acknowledged` and `override: true`, because the entry was still untested and relied on the developer option, and the session ended with **Clock sync complete**. The maintainer confirmed on the cuff that the written time took effect. The run does not record a midnight crossing or a follow-up history comparison.

Following [promoting a write aspect](#promoting-a-write-aspect), the `CLOCK_BLOCK` entry of exactly this identity is now confirmed: Sync clock appears on it without any developer option and its reports record `override: false`. The firmware name `X7 Smart AFib` resolves to this identity; HEM-7380T1-EBK, a bare `HEM-7380T1`, the encrypted X7 path and every other model are not covered. The remaining nine listed clock-block identities stay untested.

## Promoting a write aspect

Of the twelve identities listed in the [write-aspect index](safety.md#write-aspect-index), the clock block is confirmed on one (HEM-7380T1-EOSL), untested on nine and blocked on HEM-7322T and HEM-7377T1, as on every unlisted identity; unsent counters are blocked everywhere. Testers enable an untested aspect only through the hidden [developer options](development.md#developer-options), with a monitor they are prepared to lose. Moving an aspect from untested to confirmed is a maintainer code change, never a setting:

1. A tester reports a run with the exact cuff label, firmware string, Android and app versions, the device report and the result checked on the cuff (for the clock: the displayed time, including around midnight, and that settings, users and history are unchanged afterwards).
2. The maintainer checks that the report's `writes` entry names the aspect with `acknowledged` and `override: true`, that the identity matches the index entry exactly, and confirms the result with the tester.
3. The maintainer changes that one identity and aspect to confirmed in `WriteAspectIndex`, with an evidence note naming the run, updates `WriteAspectIndexTest` and records the model and operation here and in [compatibility](compatibility.md).

A confirmation covers only that identity and aspect, which includes the exact regional variants that `VendorMemoryModel.identify` maps to that identity. Before promoting, check that the run covers those variants or record which one was tested. It never carries over to another similar model, an unmapped variant or another aspect. A blocked entry cannot be promoted this way; it first needs documented source evidence and review. The only promotion so far is the clock block of HEM-7380T1-EOSL ([run](#confirmed-run-x7-smart-afib-hem-7380t1-eosl)); the explicit [clock-block write](safety.md#clock-block-write) is the only write that uses the index.

## Other device families

Repeat relevant steps per exact service layout, model and firmware. Standard BP, Optional Service RACP, UDS and time-characteristic behavior need actual captures before being marked tested. Verify explicit time writes by read-back or cuff inspection, including around midnight. STP and encrypted X7 require separate first-pair/saved-key/authentication tests. Other WLP maps remain blocked until independently implemented and reviewed; do not bypass model checks for a test.

Share a concise redacted report: model, firmware, Android/app versions, operation, expected/actual result and non-sensitive error text. Diagnostics contain health packets and identifiers; pairing bodies and consent exchanges are excluded, but this does not make the export public-safe. Review it privately before deciding what minimal evidence to share.
