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

For each exact model, compare DIS identity, service UUID and all RX/TX properties against [the map](omblepy-comparison.md). Verify first-RX subscription and Android bonding, explicit Pair key provisioning only on legacy models, and saved-key-only Connect. HEM-7377T1 must use the single-channel OS-bond path, without legacy key commands. A device exposing both a complete standard profile and vendor channels should retain the standard path.

Compare every decoded pressure, pulse, date and user with the monitor. Exercise 14-byte bank boundaries, erased slots, an unset clock, repeat imports and a naturally interrupted transfer. On BP5360 verify both user profiles from the mixed second bank, and inspect rejected summaries. Confirm no `01 C0` addressed writes or clock/unread resets occur. Do not intentionally interrupt key programming on hardware solely for release testing; synthetic tests cover lost ACK and retry recovery.

## Automatic imports and phone controls

1. With visible-app listening enabled, take a measurement and allow the cuff's normal transfer advertisement. A read-only history import should save the new reading without pressing Import. Repeated identical advertisements must remain idle after success.
2. Delete a reading locally, take another measurement and import automatically. The deleted reading must stay deleted. A deliberate manual import may restore it.
3. Test notification mode after leaving the app. On Android 13+, exercise allow/deny/re-enable notification permission and blocked New readings channel settings. One saved batch should produce one count-only alert opening History; duplicate/zero-insert attempts must not alert.
4. Test companion approval with Bluetooth off: decline the enable prompt, retry and allow it; rotate while the prompt is open. Approval must resume only for the still-saved target and selected mode. Then check background measurement arrival and expected cuff shutdown without a persistent companion-mode notification.
5. Exercise Bluetooth off/on, lost permissions, app restart, mode Off and manual Scan/Connect during automatic work. Unchanged saved revisions must remain idle across restart; failures must respect retry cooldowns. Record Android power-management restrictions when imports are delayed.
6. In History, cancel clearing, clear one bank and verify the other remains, then test All users on disposable local data. Counted confirmation spans all dates, including hidden filters. Cuff memory, saved monitors and Health Connect copies must remain. Automatic imports honor deletion receipts.
7. Check saved-profile selection and user filters across rotation/restart, including manual/unassigned records. Review localized dialogs, plural counts, long text and RTL layouts in representative languages.

A result for companion **approval** alone is not evidence of background transfer timing. Record each outcome separately.

## Health Connect

Use a phone/provider with disposable or backed-up test records. Verify initial-off state, explicit bank selection, granted/denied/revoked permission, pressure/time/count, repeated export without duplicates, new imports while UI is closed and disabling a pending queue. Excluded banks, pulse, AFib, notes and raw data must remain absent. Local deletion/disable must retain existing provider copies. Test Android 9–13 provider permission flow and Android 14+ native flow when devices are available.

## Other device families

Repeat relevant steps per exact service layout, model and firmware. Standard BP, Optional Service RACP, UDS and time-characteristic behavior need actual captures before being marked tested. Verify explicit time writes by read-back or cuff inspection, including around midnight. STP and encrypted X7 require separate first-pair/saved-key/authentication tests. Other WLP maps remain blocked until independently implemented and reviewed; do not bypass model checks for a test.

Share a concise redacted report: model, firmware, Android/app versions, operation, expected/actual result and non-sensitive error text. Diagnostics contain health packets and identifiers; pairing bodies and consent exchanges are excluded, but this does not make the export public-safe. Review it privately before deciding what minimal evidence to share.
