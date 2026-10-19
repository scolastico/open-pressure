# Safety boundaries

OpenPressure checks every outgoing characteristic write at the Android GATT boundary using `DeviceWritePolicy`: exact protocol profile, service/characteristic pair, opcode, length and operands. Descriptor writes are similarly restricted to expected reading/control CCCDs. This constrains commands sent by this implementation; it cannot prove that undocumented device firmware has no side effects.

First use requires explicit experimental-use acceptance before Bluetooth actions. Bonding, user registration and explicit clock changes, including Sync clock, can intentionally persist device configuration. There is no guarantee against incorrect operation or device damage.

## Ordinary outgoing commands

| Path | Allowed operations |
| --- | --- |
| BP / Omron Optional Service RACP | Count/report only: `04 01`, `01 01`, approved five-byte sequence-filter forms |
| User Data Service control | Exact four-byte consent `02` or selected-user registration `40`; user 1/2, code 0–9999 |
| SIG Current Time | Exact valid ten-byte local date/time, matching weekday, zero fraction, manual adjustment reason |
| Omron `5045 / 2A08` | Exact valid seven-byte Date Time |
| STP handshake | Only exact `70 01` (89 bytes), `70 05` / `70 06` (46 bytes), on a selected STP path without known WLP data banks |
| X7 read-only WLP | Exact START/READ/END; 1–16-byte reads in bounded 16-bit ranges; fresh SYS nonce transaction |
| Verified vendor memory | START/END and exact reads (1–56 bytes on four-channel legacy models, 1–16 on single-channel HEM-7377T1), each wholly inside that model’s settings window or one history bank; no addressed writes |
| Clock block (verified vendor memory, plain X7; encrypted X7 only under an [experimental switch](#experimental-developer-paths)) | Only the single planned clock-block frame of an explicit Sync clock session, gated by the write-aspect index; see [clock-block write](#clock-block-write). No Current Time or Omron Date Time write on these paths |
| Legacy application key | Only current issued control frame of an active Pair/Connect permit; model and control UUID verified; Connect never permits programming |
| Encrypted X7 | Exact handshake and authorization poll, followed by allowlisted read/session commands checked before encryption |

X7 RX/SYS descriptors allow `01 00`; standard reading/control characteristics allow only their expected `01 00` or `02 00`. Unknown descriptors, targets, opcodes and malformed operands are rejected. WLP's `C0` addressed-write flags are blocked by the ordinary policy; only the live plans of [X7 profile registration](#x7-profile-registration) and the [clock-block write](#clock-block-write) pass, each through its own check. A generic encryption wrapper cannot hide a write from the plaintext gate. Unidentified WLP4COM identity programming, unread-counter resets, record/user deletion, calibration changes and firmware commands remain unavailable.

Known WLP data-bank layouts prevent automatic selection of a speculative STP handshake. An overlapping UUID is insufficient. Actual model verification is required before any vendor memory or legacy key commands. Only the enumerated model maps and expected service/channel layouts are enabled.

## Legacy key provisioning

For identified older monitors, Pair writes a random sixteen-byte application key through the dedicated control characteristic, not through an addressed EEPROM write. This deliberately changes the monitor's application-access key and can require re-pairing with another client. A pending encrypted key is committed locally before programming; retries retain it until acknowledgment. Connect, including an automatic import, can only use saved candidates. The GATT gate requires the active state machine's exact issued command and rejects stale, changed-key or programming-on-Connect frames. See [the transport reference](protocol.md#other-vendor-memory-monitors).

Pair, Connect, history imports, Read all and automatic imports on these paths never send addressed settings/calibration writes or unread-counter resets. Their history imports read only the settings/index window and mapped banks, and raw/full EEPROM controls remain unavailable. The earlier best-effort SIG Current Time or Omron Date Time write after history and before END has been removed; an import now goes straight to END and writes nothing. The clock changes only through the explicit [clock-block write](#clock-block-write). Standard-profile monitors keep their explicit dedicated time-characteristic control unchanged. Software checks establish command bounds and state behavior; they do not establish physical-device safety or interoperability.

## X7 profile registration

This is one of two addressed-write exceptions (the other is the explicit [clock-block write](#clock-block-write)), enabled only during explicit plain-WLP **Pair** on HEM-7380T1-EOSL, or on an untested X7 identity listed in the [write-aspect index](#write-aspect-index) under the developer override. Today those untested identities are HEM-7380T1-EBK, whose original configuration matches the EOSL apart from its model ID, and the experimental HEM-7196T1-FLE and HEM-7196T1-FLEO, whose Omron configuration has the same settings write layout (`0x54` prefix, block 3 at `0x80`) with a 60-slot index; they run the identical plan, frames and checks. **Connect**, history, range reads, Fetch now, automatic imports and the encrypted X7 route retain the ordinary read-only policy.

The gate resolves the verified model with `WriteAspectIndex.resolve` and starts registration only when the index permits the `REGISTRATION` aspect: confirmed on HEM-7380T1-EOSL (including the `X7 Smart AFib` firmware name), untested on HEM-7380T1-EBK, HEM-7196T1-FLE and HEM-7196T1-FLEO and blocked everywhere else, including a bare `HEM-7380T1` or `HEM-7196T1` and the catalog names `M4 Connect AFib` / `X4 Connect AFib`. The override comes only from the hidden [developer option](development.md#developer-options), read through the same private helper as the clock override when Pair reaches the ready state and again when the user confirms; a failure to read it counts as off. `X7Registration` is bound to the resolved identity and carries the override read at confirmation, and `DeviceWritePolicy.validateX7Registration` calls `requireWriteAspect(identity, REGISTRATION, override)` before the live permit is consulted. When registration is not permitted, Pair ends in the read-only ready state ("Profile registration is not enabled for <identity>") without reading the settings for registration. If the switch was turned off or the options hidden before confirmation, confirming ends the session without a write. Pair asks for the user **on the phone**; Android bonding and a SYS nonce reply alone do not complete profile registration.

### Preconditions and backup

The app verifies the actual model and plain protocol, completes fresh Android bonding and SYS verification, sends START and reads exactly 68 bytes at `0x0010`. It validates the index and block-3 checksum, then saves the original image in a unique private `settings-backups/` JSON file. It flushes the file before atomic publication and syncs the directory and parent before permitting registration. Storage failure, cancellation, stale callbacks or a changed connection cannot enable a write.

The backup contains the device/model identifiers, original 68 bytes, checksum validation and SHA-256. `read_complete=true` describes the index capture; `session_ended=false` records that the registration session was still open when saved. Each attempt retains a separate file, distinct from ordinary dump snapshots. It is a settings/index snapshot, not a physical EEPROM or calibration backup, and does not provide an automatic restore facility.

The user selects bank 1 or 2, confirms that the cuff's label is exactly the identity the app names (HEM-7380T1-EOSL, or HEM-7380T1-EBK, HEM-7196T1-FLE or HEM-7196T1-FLEO under the override, where an untested-monitor warning is shown) and approves the intended selected-user/clock changes. The immutable plan belongs to that connection and snapshot.

### Exact write bounds

Offsets below are relative to the 68-byte image beginning at `0x0010`. Only the selected-user bit at relative byte 48, local clock bytes 52–57 and checksum byte 58 may change. The other user's bit, birthdays, revision values, unread/history pointers and reserved bytes remain unchanged. Clock uses phone local time plus one second, matching the inspected original workflow; the implementation accepts years 2024–2050 and rejects adjustment beyond that range.

| Stage | Destination | Payload |
| --- | --- | --- |
| Pointer prefix | `0x0054` | Exact unchanged first 24 bytes of the captured index |
| Optional settings block 3 | `0x0080` | Exact planned 16-byte block, with only the permitted fields changed |

Block 3 sums relative bytes 44–57 into byte 58; byte 59 is reserved. The prefix must not be stripped and the payload shifted to the staging base. ATT MTU must be at least 35. A separate live registration permit allows only the exact current frame; the ordinary policy still rejects both addressed writes. No generic addressed-write control is exposed.

Write acknowledgment can be compact eight bytes or an exact full echo: 32 bytes for the prefix write, 24 for the settings-block write. The parser checks response opcode `81`, flags `C0`, address, requested length, zero status and XOR. Every echoed data byte must match the immutable planned payload. Fragment reassembly is bounded; malformed/unexpected replies revoke permission. The second write waits for the first ACK. END waits for both writes, and registration is reported acknowledged only after a validated END ACK.

Interruption after a write but before END leaves device state unverified. The app retains the backup and does not automatically retry or send compensating writes. The staged addresses and exact bytes constrain intended behavior; they do not independently establish every undocumented firmware effect.

### Original registration evidence

The source is the original APK identified by SHA-256 in the [protocol reference](protocol.md#source-artifacts-and-traceability), independently implemented without copying executable code:

- `assets/Device/device_models.json` for HEM-7380T1-EOSL and `VoltronMultiUserFragment` provide the phone's user-choice workflow.
- `BaseOmronConnectivity.u()` passes settings through `OmronCommLibManager.r()` to `OGSCDevice.g([selectedUser], settings, callback)`.
- `ogsc.a.j6.D()` removes unchanged user attributes. Preserving them avoids replacing birthdays or incrementing revision field `1013`; that field is a DCI revision counter, not a generated secret.
- `ogsc.a.p4.a()` adds `0024=1` for the selected user: relative byte 48, bit 0/1 for bank 1/2. No public semantic name was recovered; its role is established by this selected-user settings path.
- `j6.g()` creates `OCLTimeSetting`; model `timeUpdateRule=0` causes `p4` to supply time through field `100e`, bytes 52–57.
- `h0.z()` recomputes additive block checksums. `j6.M()` stages the full pointer prefix and selected changed optional blocks at `index_setting_write_address=0x0054`; with attributes retained, only block 3 changes at `0x0080`.
- `OHQWlCommand.k()` constructs addressed writes; `e()` checks the echoed header, `d()` checks XOR, `s()` checks status and `p()` permits full response payloads. The original settings workflow ends the session before reporting success.
- `100f` is synthesized by `ogsc.a.h0.j()` by comparing attributes against model `defaultCheck` values. It is not a dedicated registration byte and does not authorize a guessed single-byte write.

Detailed captured evidence confirms both staged write echoes and END, followed by a separate complete 111/111-record history transfer on the tested EOSL cuff. The user confirmed pairing and correct time. This result does not establish general compatibility with other X7 variants; the EBK entry stays untested until a run is reported ([validation](hardware-validation.md#validating-registration-on-hem-7380t1-ebk)).

## Clock-block write

**Sync clock** is the second addressed-write exception. It writes only the clock block of the settings image, once, in its own explicitly started session on a verified vendor-memory model or plain-WLP X7. Pair, Connect, Import, Read all and automatic imports never send it and cannot start it. Everything in this section is source-derived and covered by synthetic tests and fixtures. One physical run confirms it on exactly one identity: on 2026-10-18 a plain-WLP X7 Smart AFib (HEM-7380T1-EOSL) acknowledged and applied the write ([hardware validation](hardware-validation.md#confirming-clock-sync)), so that entry of the [write-aspect index](#write-aspect-index) is confirmed; every other clock-block entry is untested or blocked.

### Gate

`MonitorService.canClockSync()` offers Sync clock only on a fresh, idle, manual **Connect** session: the monitor is ready and verified, nothing else (history, Read all, memory read, registration) has run on the connection, and it is neither Pair nor an automatic import. A vendor-memory session must have the verified model's map and all of its TX channels; an X7 session must be plain WLP with a verified SYS transaction and a negotiated ATT MTU of at least 27, so the 24-byte frame fits one write. The encrypted X7 path is excluded and shows no clock control, unless the [experimental switch](#experimental-developer-paths) is on and the encrypted session is established and authorized. The resolved identity must have a `ClockBlock` layout, and `WriteAspectIndex` must permit `CLOCK_BLOCK` for it. The app shows the button only when that permission part holds (`clockSyncOffered()`: the entry is confirmed, or untested with the developer override); otherwise vendor-memory and X7 connections show no clock control. HEM-7380T1-EOSL is confirmed, so the button appears there without any developer option; every other listed identity is untested, so it appears there only with the developer override; HEM-7322T, HEM-7377T1 and every unlisted identity are blocked and never pass. The override is recorded (in the plan, the report and the confirmation's untested warning) only for an untested entry; a confirmed entry never relies on it.

The override is read at request time, never cached: through one private `MonitorService` helper, when the button's availability is evaluated, when the settings image arrives and again when the user confirms. A failure to read it counts as off. The plan built at confirmation carries the value read then, the GATT boundary checks `requireWriteAspect` with it for every chunk, and the device report records it. On an untested model, if the switch was turned off before confirmation, confirming ends the session without a write; if the options were hidden, a developer-chosen time is refused first while the confirmation stays open for Cancel or the phone time, and confirming the phone time then ends the session without a write. This satisfies the fail-safe rule of the [override rule](#override-rule): a lock or switch change whose save failed can come back after a restart, but each write still requires a fresh confirmation, and while the override is on the confirmation shows the untested-monitor warning. A [developer-chosen time](development.md#developer-options) needs only unlocked developer options, on confirmed and on overridden-untested monitors alike, and is read through its own private helper: when the settings image arrives and again at confirmation, where locked options refuse it. It chooses only the target of a write the index already permits; it never permits a write.

### Session order and preconditions

1. START, then a read of the model's whole settings image in the same session ([layouts](protocol.md#clock-block-layouts)). Nothing is written before confirmation.
2. `VendorClockWrite` accepts the image only when it has the exact image length and the block's additive checksum is valid on the logical (un-swapped) bytes. An erased block (all `FF`) is accepted only where Omron's code accepts one: HEM-7150T, HEM-7155T, HEM-7156T, HEM-7342T, HEM-7361T and HEM-6232T (`ClockBlock.erasedAllowed`). On HEM-7380T1-EOSL/EBK, HEM-7196T1-FLE/FLEO, HEM-7530T and HEM-7600T, Omron and OpenPressure's own index and registration paths refuse a block 3 with a bad checksum, so an erased block there is refused too. Otherwise the session reports the settings as unreadable and sends END without writing.
3. The target is the phone's local time, truncated to whole seconds, or a [developer-chosen time](development.md#developer-options). The written clock is target + 1 s, matching the original workflow, and must lie in 2024–2050. If the phone's own date is outside that range, the session reports that separately (not as unreadable settings) and sends END without writing; an out-of-range developer-chosen time is refused while the confirmation stays open. When the stored clock decodes and differs from the target by at most 60 seconds, nothing is written.
4. The confirmation shows the monitor time, phone time and difference. Without an answer within 90 seconds, the session ends without a write. On confirmation the plan is rebuilt from the same session's image with the time read at that moment.
5. Before authorization the app saves the image read in this session as `settings-backups/clock-sync-settings-<time>-<uuid>.json` (`open-pressure-settings-backup-v1`, kind `clock-sync`, model, start address, length, hex data and SHA-256), flushes it and syncs the directory. A storage failure, a 15-second backup timeout, cancellation or a changed connection ends the session without a write. These files stay separate from X7 pre-registration backups; the newest one can be exported from its own card under Advanced (**Export settings backup**), and none is ever restored automatically. They are a settings-image copy, not a full EEPROM or calibration backup.
6. The plan is authorized once and sent as one frame, split into chunks ([frame and chunking](protocol.md#clock-block-write-frame)). Each chunk's permit is consumed when the GATT boundary grants it; the next chunk follows the previous write's success callback. A failed GATT write is never retried.
7. After the last chunk the app waits up to 10 seconds for the acknowledgment. After a valid or rejected acknowledgment, and when it ends without writing (unless the connection failed), it sends the normal END; without any acknowledgment it closes the connection instead. The connection is finished afterwards; importing history needs a new connection.

### Data and acknowledgment

Only the six clock bytes (year − 2000, month, day, hour, minute, second) and the block's additive checksum change; every other block byte, including the reserved last byte and the `FF` bytes of an erased block, is copied unchanged from the image read in this session. The acknowledgment can be the compact eight bytes or the full echo of the frame. The parser requires response opcode `81`, flags `C0`, the address and count of the request, zero status and a valid XOR; every byte of a full echo must match the frame. Any other reply revokes the plan.

| Report outcome | When | Monitor state |
| --- | --- | --- |
| `acknowledged` | The monitor acknowledged the write | Clock expected to hold the written time; check it on the monitor. A lost END after the acknowledgment does not change this outcome |
| `skipped_within_threshold` | Stored clock within 60 s of the target | Nothing written |
| `rejected` | A reply that is not a valid acknowledgment | Write sent, effect unverified; no retry, END still follows |
| `cancelled` | No acknowledgment: timeout, disconnect or GATT failure after the write began | Effect unverified; backup retained; no retry or further write. A timeout closes the connection without END |
| `not_attempted` | Cancelled or unanswered confirmation, unreadable settings, phone date outside 2024–2050, backup failure, withdrawn override or a failure before the write | Nothing written |

### What the clock sync never writes

Within the block it changes nothing but the clock and checksum; the X7 user-selection bit at byte 48 is written back unchanged. It never writes the index prefix (`0x54`, `0x2A4` or `0x286`), unsent counters, birthday blocks, any other settings block, calibration or any other address, and it never writes during Pair, Connect, imports or automatic imports. Omron Connect writes the index prefix before the clock block after every transfer; OpenPressure writes the clock block alone. The HEM-7380T1-EOSL accepted and applied a clock-block write without that prefix; on every other model this is unverified on hardware.

On the X7 the same block (`0x80`, image bytes 44–59) is also written by [profile registration](#x7-profile-registration), together with the prefix and the user bit. The standalone clock write sends no prefix and leaves the user bit unchanged; it does not change or extend the registration exception. Its confirmation on HEM-7380T1-EOSL is a separate index entry and grants Connect no registration or key-programming privilege.

## Write-aspect index

The twelve identities implemented from the [Bluetooth reference batch](bluetooth-coverage.md) inherit no older identity's clock or registration permission. They resolve to their own exact identities and remain unlisted in this table, so every memory-write aspect is blocked even when a developer override is on. Legacy explicit Pair may provision only its application key through the existing short-lived unlock permit. The new single-channel paths additionally validate each read against their own settings image or history bank at the GATT boundary and before encryption; arbitrary dumps are disabled there.

> [!WARNING]
> **Writes marked untested here are not confirmed for the model. Such a write can permanently corrupt the monitor's settings or make it unusable. Overriding them is meant only for people validating a model with a monitor they are prepared to lose.**

`WriteAspectIndex` records, per exact verified model identity, whether a memory-write aspect is **confirmed** on hardware, **untested** or **blocked**. It is data only: it builds no frame and authorizes no byte. A permitted aspect still has to pass every model-bound check in `DeviceWritePolicy` at the GATT boundary. The [clock-block write](#clock-block-write) and the [X7 profile registration](#x7-profile-registration) are the only writes that use it; no unsent-counter reset or other new memory write exists.

| Aspect | Status | Identities |
| --- | --- | --- |
| `CLOCK_BLOCK` (clock fields in the settings block) | Confirmed 2026-10-18: acknowledged and applied on X7 Smart AFib, firmware SI.W03.00.03 ([run](hardware-validation.md#confirming-clock-sync)) | HEM-7380T1-EOSL |
| `CLOCK_BLOCK` | Untested | HEM-7150T, HEM-7155T, HEM-7156T, HEM-7342T, HEM-7361T, HEM-7530T, HEM-7600T, HEM-6232T, HEM-7380T1-EBK |
| `CLOCK_BLOCK` | Untested: Omron's HEM-7196T1 configuration matches HEM-7380T1-EOSL apart from `model_id`, slots per user and the user 2 bank (same block 3 at `0x80`), no run yet | HEM-7196T1-FLE, HEM-7196T1-FLEO |
| `CLOCK_BLOCK` | Blocked: no documented clock-block layout | HEM-7322T, HEM-7377T1 |
| `UNSENT_COUNTERS` | Blocked: not implemented | Every identity |
| `REGISTRATION` (explicit plain-WLP Pair registration) | Confirmed: tested registration including its clock on the maintainer's cuff ([evidence](hardware-validation.md#confirmed-evidence)) | HEM-7380T1-EOSL |
| `REGISTRATION` | Untested: identical EOSL/EBK configuration except `model_id`, no run yet | HEM-7380T1-EBK |
| `REGISTRATION` | Untested: Omron's HEM-7196T1 configuration has the HEM-7380T1-EOSL settings write layout (`0x54` prefix, block 3 at `0x80`), no run yet | HEM-7196T1-FLE, HEM-7196T1-FLEO |
| `REGISTRATION` | Blocked: no registration workflow for this identity | Every other listed identity |
| Any aspect | Blocked: no evidence | Every unlisted identity |

Only the clock block and the registration of HEM-7380T1-EOSL are confirmed; no other identity or aspect is. "Untested" means only that a source-derived layout exists; it has not been exercised on that model. Lookups use the exact identity: a vendor-memory model's table id or an X7-family identity (`HEM-7380T1-EOSL`, `HEM-7380T1-EBK`, `HEM-7196T1-FLE`, `HEM-7196T1-FLEO`). Callers resolve a verified model string with `WriteAspectIndex.resolve` first: it removes NULs and surrounding whitespace, maps only the documented exact regional variants that `VendorMemoryModel.identify` knows to their table id (for example HEM-7155T-EBK, -D, -ESL and -AP to HEM-7155T, or HEM-7377T1-ZAZ to HEM-7377T1), maps the firmware name `X7 Smart AFib` (any letter case) to `HEM-7380T1-EOSL`, matching the device report's verified-model rule; `HEM-7196T1-FLE` and `HEM-7196T1-FLEO` pass only as exact strings. Anything else, including a bare `HEM-7380T1` or `HEM-7196T1` and the catalog names `M4 Connect AFib` / `X4 Connect AFib`, is left unchanged and is blocked; no other similar name, alias or shared UUID maps to a listed entry. The `CLOCK_BLOCK` entries concern the standalone clock-block write; the `REGISTRATION` entries gate the [X7 profile registration](#x7-profile-registration) exception, whose clock bytes remain part of that exact Pair plan. Neither aspect extends the other.

### Override rule

`DeviceWritePolicy.requireWriteAspect(identity, aspect, override)` rejects unless the status permits it: **confirmed** passes; **untested** passes only when that single request carries an explicit override; **blocked** never passes, override or not. The override is an argument of each request, never a stored global in the protocol layer. In the app it can come only from the matching hidden [developer option](development.md#developer-options), which counts only while those options are unlocked, starts off after every unlock and is never read by Connect, Import, Read all, Fetch now or automatic imports; Pair reads only the registration switch. Any action that reads a developer switch must fail safe: it must treat a lock whose save failed as not locked, and it must confirm the switch again when the request is made. Sync clock does so as described under [gate](#gate); registration reads it when Pair is ready and again at confirmation. Promotion from untested to confirmed is a maintainer code change described in [hardware validation](hardware-validation.md#promoting-a-write-aspect).

### Device report writes section

A [device report](device-reports.md) lists at most eight attempted write aspects under `writes`. Each entry has only `aspect` (`clock_block`, `unsent_counters` or `registration`), `outcome` (`acknowledged`, `rejected`, `skipped_within_threshold`, `cancelled` or `not_attempted`) and `override` (whether an untested aspect was allowed by explicit override). It never contains the written values, the selected user, the phone or monitor time, addresses or memory contents. Recording it sends nothing to the monitor. Each manual Sync clock session records one `clock_block` entry with its outcome; each registration the user confirmed records one `registration` entry (`acknowledged` after the validated END, `rejected` for an invalid write acknowledgment, `cancelled` when the session ended after a write began, `not_attempted` when the confirmation was refused before any write); automatic imports record none. When the override was on and a write was actually sent (`acknowledged`, `rejected` or `cancelled`), the app offers the device report once; a skip within the threshold or a session without a write offers nothing. A confirmed entry never relies on the override, so a clock write or registration on HEM-7380T1-EOSL records `override: false` and offers no report; the recorded write still makes its report available on the card, and the tested identity is never asked after Pair.

A separate `developer` list names the [experimental switches](#experimental-developer-paths) that were in effect for the session (`force_x7_encrypted`, `clock_sync_encrypted_x7`), names only.

### What is never written

The index does not open any of the following, on any model and with any developer option: calibration or unknown settings addresses, firmware or update commands, record or user deletion, generic or unrestricted addressed writes, guessed registers, or a write to a model whose identity was not verified. Unsent-counter resets stay unavailable. Software and synthetic tests (`WriteAspectIndexTest`, `VendorWritePolicyTest`, `X7RegistrationTest`, `ClockBlockTest`, `VendorClockWriteTest`, `MonitorServiceClockSyncTest`, `MonitorServiceRegistrationGateTest`, `MonitorServiceSecureX7Test`, `MonitorServiceHem7196Test`, `DeviceReportTest`) establish only the gate's behavior, not that any write is safe on a physical monitor.

## Experimental developer paths

> [!WARNING]
> **These switches unlock connection paths that exist in code but have never completed against a physical monitor. They may simply fail, and a failed write can leave a monitor's settings unverified. They are meant only for people validating a model with a monitor they are prepared to lose.**

Two hidden [developer switches](development.md#experimental-paths) select already-implemented, hardware-untested paths, because there is no other way to exercise them on hardware. They count only while the developer options are unlocked and start off after every unlock.

- **Forced encrypted X7 route.** A user-started **Pair** on an X7 advertising plain WLP selects `X7_SECURE_WLP`: the existing fresh application pairing (`70 01`, `70 05`, `70 06`) and the authorization poll, with acknowledged SYS writes and an ATT MTU of at least 92 still required. No profile registration happens in that session, because registration exists only on the plain route, and the Android bond is not renewed. Connect, automatic imports and Fetch now never read the switch.
- **Clock sync over an encrypted X7 session.** On a manual Connect whose encrypted session is established and authorized, Sync clock sends the same single clock-block frame. `VendorClockWrite` records the encrypted transport, and `validateX7ClockBlock` accepts such a plan only on `X7_SECURE_WLP` (and a plain plan only on `X7_WLP`). The write-aspect gate is unchanged.

What stays: every post-handshake command is still checked in plaintext against the same validators before `SecureSession` encrypts it, incoming frames still pass integrity, counter and replay checks, there is still no generic encrypted-write escape, and the switches authorize no new command, address or model. They never enable registration, key programming or any write on Connect or automatic imports beyond the explicit Sync clock above.

## Memory and firmware limits

The X7 logical read map is settings/index `[0x0010,0x0054)` and populated 16-byte BP slots in the two 100-slot banks at `0x01C4` and `0x0804`; on the experimental HEM-7196T1 the two banks have 60 slots, at `0x01C4` and `0x0584`. The index and the history ranges are bound to the verified identity's `X7Model`, and the app checks each range against that model's banks before reading it. Each X7 import reads a fresh index and never intentionally probes empty slots, gaps or calibration locations. The separate PPW region at `0x010000` lies outside exposed 16-bit reads.

A requested JSON dump is complete only for its requested range plus END. A successful default read does not establish unrestricted physical EEPROM access. Address zero and a boundary-crossing error-region read have returned E3, with Err observed on the cuff. E3's universal meaning is unknown. There is no automatic full-address sweep or write fallback. Unknown calibration addresses are not assumed safe.

No Bluetooth firmware-image read command has been identified. A Device Information firmware string is a version label, not firmware contents. Executable storage location and hardware flash/debug extraction have not been established; a hypothetical EEPROM image should not be assumed to include executable firmware. Firmware/update commands remain blocked.

## External safety comparison

An earlier user-requested source audit compared `userx14/omblepy` commit [`f323d5ae58693cc737c81c1a1e7ff6ac067c4664`](https://github.com/userx14/omblepy/tree/f323d5ae58693cc737c81c1a1e7ff6ac067c4664). No code or model parser was imported. These references explain why generic settings writes were not adopted:

| Pinned source | Relevant observation |
| --- | --- |
| [README flags warning](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/README.md#flags-table) | Calibration may share settings storage; time/counter changes are model-sensitive, with no universal verified calibration range |
| [`omblepy.py`, `_writeBlockEeprom`](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/omblepy.py#L168-L185) | Raw opcode `01` writes with `C0` flags, address, length and data |
| [`sharedDriver.py`, `getRecords`](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/sharedDriver.py#L38-L89) | Optional model-specific time/unread writes accompany record reading only when requested |
| [`hem-7361t.py`](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7361t.py) | An older model's settings map must not be transplanted to another X7 generation |
| [`hem-7380t1.py`](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7380t1.py#L11-L28) | FE4A WLP, OS bonding, no legacy application unlock; time/unread settings slices unset |

The present X7 registration map comes independently from the original EOSL assets and workflow, rather than inferred offsets from that external client. Automated command-policy tests exercise allowed frames and reject unknown/deletion/calibration/firmware paths. Software tests do not establish firmware safety on an untested model.
