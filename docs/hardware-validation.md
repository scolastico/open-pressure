# Hardware validation

## Confirmed evidence

The tested monitor is **Omron X7 Smart AFib HEM-7380T1-EOSL**, plain WLP manufacturer type 6. The user reports version 0.1.13 working. Detailed supplied evidence establishes:

- Correct FE4A WLP channel layout and exact `X7 Smart AFib` DIS alias.
- Valid padded SYS nonce reply; the incoming response can be 244 bytes while the request remains exactly 20.
- Complete default settings/index read: 68 bytes at `[0x0010,0x0054)`, followed by validated END, then expected cuff shutdown about 3.2 seconds later.
- Both bounded registration-write echoes and END, then a separate complete 111/111-record history transfer. The user confirmed pairing, correct clock and a newly taken measurement imported manually with 0.1.10.
- Android companion approval on Pixel 9 Pro, Android 17 build `CP3A.260905.009`, after Bluetooth was enabled. The reported endless search was caused by Bluetooth being off.

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

On an index-supported model (HEM-7150T, 7155T, 7156T, 7342T, 7361T, 7530T, 6232T), run **Import monitor history**, then reconnect and run **Read all** without taking a new measurement. Read all should then add no new readings from the chosen users' banks, because the index-driven import already found them all; on HEM-7156T, readings from its second area are the exception described in the HEM-7156T paragraph below. The first summary should report the monitor's stored-reading count rather than the fallback message. Record the reported count against the readings shown on the monitor, including a full bank that has wrapped and an empty bank when practical. A fallback message on an unmodified monitor means the index rule does not match that firmware; keep the diagnostics. On a two-user model, check **Readings to import** for User 1, User 2 and both. Record checksums are now enforced on these maps and on HEM-7600T, so any rejected slot on a valid monitor reading is a finding to report. Exercise 14-byte bank boundaries, erased slots, an unset clock, repeat imports and a naturally interrupted transfer. On BP5360 verify both user profiles from the mixed second bank, and inspect rejected summaries. Confirm no `01 C0` addressed writes or EEPROM clock/unread resets occur. Do not intentionally interrupt key programming on hardware solely for release testing; synthetic tests cover lost ACK and retry recovery.

For HEM-7156T, verify discovery when only a mixed-case `BLESmart_…` name is advertised. Pair should proceed to user 1's history on the same connection. Then use **Read all** and record how the monitor answers for the second bank at `0x458`: records, erased slots, an error status or no reply. Omron's configuration does not list that bank, and its real behavior is unknown; the app should skip it without failing. If a dedicated time characteristic is present, verify that its optional update happens before the only END and that a naturally rejected update preserves the imported history. Without one, expect an explicit skipped-clock result and no EEPROM clock write. A disconnect during clock/finalization must retain history and report the missing END acknowledgment; a disconnect before all history arrives must remain an interrupted transfer.

## Automatic imports and phone controls

1. With visible-app listening enabled, take a measurement and allow the cuff's normal transfer advertisement. A read-only history import should save the new reading without pressing Import. Repeated identical advertisements must remain idle after success.
   On a vendor-memory model, first record the manufacturer data shown during Scan: an automatic import requires type 1, 2 or 6. Verify that the import uses only the saved-key unlock, follows **Readings to import**, reads the index first on index-supported models, sends no clock write, and that the link closes after END.
2. Delete a reading locally, take another measurement and import automatically. The deleted reading must stay deleted. A deliberate manual import may restore it.
3. Test notification mode after leaving the app. On Android 13+, exercise allow/deny/re-enable notification permission and blocked New readings channel settings. One saved batch should produce one count-only alert opening History; duplicate/zero-insert attempts must not alert.
4. Test companion approval with Bluetooth off: decline the enable prompt, retry and allow it; rotate while the prompt is open. Approval must resume only for the still-saved target and selected mode. Then check background measurement arrival and expected cuff shutdown without a persistent companion-mode notification.
5. Exercise Bluetooth off/on, lost permissions, app restart, mode Off and manual Scan/Connect during automatic work. Unchanged saved revisions must remain idle across restart; failures must respect retry cooldowns. Record Android power-management restrictions when imports are delayed.
6. In History, cancel clearing, clear one bank and verify the other remains, then test All users on disposable local data. Counted confirmation spans all dates, including hidden filters. Cuff memory, saved monitors and Health Connect copies must remain. Automatic imports honor deletion receipts.
7. Check saved-profile selection and user filters across rotation/restart, including manual/unassigned records. Review localized dialogs, plural counts, long text and RTL layouts in representative languages.

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

Pair an untested monitor and let the session end. The report question must appear once, after the monitor disconnects, and never for the EOSL cuff or during automatic imports. Review the report text in a long-text and an RTL language: it must stay left-to-right and contain no address, serial number, reading or timestamp. **Copy and open GitHub** must open the Device report form with the model filled in, and the clipboard must hold the report. **Save file** must write the same JSON.

With update checks on and an older installed version, the update dialog must appear when the app opens once the newer release is known. **Remind me later** and dismissing must suppress it for three days; **Skip this version** only for that version. Turning checks off under Devices → Advanced must stop all requests; verify with a network log if available.

## Other device families

Repeat relevant steps per exact service layout, model and firmware. Standard BP, Optional Service RACP, UDS and time-characteristic behavior need actual captures before being marked tested. Verify explicit time writes by read-back or cuff inspection, including around midnight. STP and encrypted X7 require separate first-pair/saved-key/authentication tests. Other WLP maps remain blocked until independently implemented and reviewed; do not bypass model checks for a test.

Share a concise redacted report: model, firmware, Android/app versions, operation, expected/actual result and non-sensitive error text. Diagnostics contain health packets and identifiers; pairing bodies and consent exchanges are excluded, but this does not make the export public-safe. Review it privately before deciding what minimal evidence to share.
