# Safety boundaries

OpenPressure checks every outgoing characteristic write at the Android GATT boundary using `DeviceWritePolicy`: exact protocol profile, service/characteristic pair, opcode, length and operands. Descriptor writes are similarly restricted to expected reading/control CCCDs. This constrains commands sent by this implementation; it cannot prove that undocumented device firmware has no side effects.

First use requires explicit experimental-use acceptance before Bluetooth actions. Bonding, user registration and explicit clock changes can intentionally persist device configuration. There is no guarantee against incorrect operation or device damage.

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
| Verified vendor clock characteristic | Optional valid SIG Current Time or Omron Date Time write after history, before END, in explicit sessions only; skipped if unsupported and during automatic imports; no EEPROM write |
| Legacy application key | Only current issued control frame of an active Pair/Connect permit; model and control UUID verified; Connect never permits programming |
| Encrypted X7 | Exact handshake and authorization poll, followed by allowlisted read/session commands checked before encryption |

X7 RX/SYS descriptors allow `01 00`; standard reading/control characteristics allow only their expected `01 00` or `02 00`. Unknown descriptors, targets, opcodes and malformed operands are rejected. WLP's `C0` addressed-write flags are blocked by the ordinary policy. A generic encryption wrapper cannot hide a write from the plaintext gate. Unidentified WLP4COM identity programming, unread-counter resets, record/user deletion, calibration changes and firmware commands remain unavailable.

Known WLP data-bank layouts prevent automatic selection of a speculative STP handshake. An overlapping UUID is insufficient. Actual model verification is required before any vendor memory or legacy key commands. Only the enumerated model maps and expected service/channel layouts are enabled.

## Legacy key provisioning

For identified older monitors, Pair writes a random sixteen-byte application key through the dedicated control characteristic, not through an addressed EEPROM write. This deliberately changes the monitor's application-access key and can require re-pairing with another client. A pending encrypted key is committed locally before programming; retries retain it until acknowledgment. Connect, including an automatic import, can only use saved candidates. The GATT gate requires the active state machine's exact issued command and rejects stale, changed-key or programming-on-Connect frames. See [the transport reference](protocol.md#other-vendor-memory-monitors).

These paths never send addressed settings/calibration writes, EEPROM clock sync or unread-counter resets. Their history imports read only the settings/index window and mapped banks, and raw/full EEPROM controls remain unavailable. A writable dedicated time characteristic permits a best-effort clock update after history and before END. Its failure cannot revoke readings already imported. Missing time characteristics do not trigger a settings-write fallback. Software checks establish command bounds and state behavior; they do not establish physical-device safety or interoperability.

## X7 profile registration

This is the sole addressed-write exception, enabled only during explicit plain-WLP **Pair** for the HEM-7380T1-EOSL workflow. **Connect**, history, range reads and automatic imports retain the ordinary read-only policy. Pair asks for the user **on the phone**; Android bonding and a SYS nonce reply alone do not complete profile registration.

### Preconditions and backup

The app verifies the actual model and plain protocol, completes fresh Android bonding and SYS verification, sends START and reads exactly 68 bytes at `0x0010`. It validates the index and block-3 checksum, then saves the original image in a unique private `settings-backups/` JSON file. It flushes the file before atomic publication and syncs the directory and parent before permitting registration. Storage failure, cancellation, stale callbacks or a changed connection cannot enable a write.

The backup contains the device/model identifiers, original 68 bytes, checksum validation and SHA-256. `read_complete=true` describes the index capture; `session_ended=false` records that the registration session was still open when saved. Each attempt retains a separate file, distinct from ordinary dump snapshots. It is a settings/index snapshot, not a physical EEPROM or calibration backup, and does not provide an automatic restore facility.

The user selects bank 1 or 2, confirms the cuff's exact HEM-7380T1-EOSL label and approves the intended selected-user/clock changes. The immutable plan belongs to that connection and snapshot.

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

Detailed captured evidence confirms both staged write echoes and END, followed by a separate complete 111/111-record history transfer on the tested EOSL cuff. The user confirmed pairing and correct time. This result does not establish general compatibility with other X7 variants.

## Memory and firmware limits

The X7 logical read map is settings/index `[0x0010,0x0054)` and populated 16-byte BP slots in the two 100-slot banks at `0x01C4` and `0x0804`. Each X7 import reads a fresh index and never intentionally probes empty slots, gaps or calibration locations. The separate PPW region at `0x010000` lies outside exposed 16-bit reads.

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
