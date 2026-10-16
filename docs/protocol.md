# Protocol reference

This reference describes the independently implemented blood pressure paths. It separates facts recovered by static inspection from captured behavior. The [compatibility guide](compatibility.md) records what has actually been tested. No original executable code, Omron SDK or proprietary native binary is included, linked or loaded.

## Source artifacts and traceability

The original supplied [decompilation notes](reference/README.md) remain verbatim. Subsequent inspection used `com.omronhealthcare.omronconnect-2026091010_arm64-v8a.xapk`:

| Artifact | SHA-256 |
| --- | --- |
| Contained `base.apk` | `ce6550f7f9ecb52d488ced25fd421a6390ed5a239761784dc5f7510f088bab9d` |
| Statically inspected `libnativeOmsec.so` | `a81351f72021fc93de7fbba526d4e3bd559a00887907f2967218015ca7f38965` |

Research used JADX class/source inspection, REA Android class inventory and LLVM ARM64 disassembly. Original-app execution was not used for those findings. Function addresses below are virtual addresses in the inspected artifact, not runtime addresses. The native inspection covers the listed STP routines, not every function in the library. Physical results came separately from user reports and supplied diagnostic exports, which are not distributed here.

## Protocol selection

A vendor UUID alone is insufficient to choose STP. WLP also uses `FE4A` / `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` and control characteristic `b305b680-aee7-11e1-a730-0002a5d5c51b`. Discovery checks complete service/characteristic layouts. Known WLP data-bank layouts cannot trigger a speculative STP handshake. Where a complete standard BP/Optional Service path exists it can be used without speculative vendor writes.

X7 memory access additionally requires DIS model `180A / 2A24` to equal `HEM-7380T1`, `HEM-7380T1-EOSL` or `X7 Smart AFib`, case-insensitive, plus the expected WLP channels. Substrings and a selected UI model do not authorize it. The exact marketing name was reported by the tested EOSL cuff. Aliases are not evidence that other physical variants were tested. Other supported vendor maps require their own strict DIS identity and channel verification, as described below; unknown maps remain rejected.

`OHQBleDevice.m()` selects WLP1COM_PAIR when manufacturer type is 8/9 or a saved application LTK exists. Android `getManufacturerSpecificData(0x020e)` strips the company prefix: type is payload byte 0, corresponding to raw byte 2. `OHQBleManager.c()` recognizes plain types 1/2/3/6 and encrypted types 8/9. Missing or unknown data cannot select a new security mode. A saved LTK selects encryption even if the current advertisement reports a plain type. The tested cuff advertised type 6, plain WLP. A saved monitor lacking sufficient metadata is scanned by exact address before resuming.

## Standard blood pressure paths

SIG BP uses `1810 / 2A35`; parser support includes SFLOAT special values, kPa, optional timestamps/pulse/user/status and truncated-field rejection. Missing fields are not fabricated.

The inspected original APK establishes an additional BP-capable history path:

| Original source | Observation |
| --- | --- |
| `jp.co.ohq.ble.OHQUUIDDefines.Service` | Optional Service `5df5e817-a945-4f81-89c0-3d4e9759c07c` |
| `OHQDevice.NotificationEnablingState` | Subscribes BP Measurement, UDS control and Optional Service RACP as enabled |
| `OHQDevice.MeasurementRecordAccessControllingState` | Counts, then reports stored records through Optional Service `2A52` |
| `OHQDevice.NotificationEnabledState` / `BloodPressureMeasurement` | Parses BP from `2A35` |
| `RecordAccessControlPoint` | `04 01` count-all; `01 01` report-all; operator `03`, filter `01`, uint16 LE sequence for sequence filters |
| `RecordAccessControlPoint` | Vendor latest-sequence request `10 00`; response `11 00 lo hi`. This source observation is not an unrestricted outgoing-command allowance. |

The separate `GlucoseCustomCommandResponseAnalyzer` expects a 15-byte glucose-shaped payload: flags, sequence, timestamp, offset, one SFLOAT and type/location nibbles. That does not establish systolic/diastolic/pulse fields. GLS_EX `504A/504B` is therefore not decoded as BP.

`jp.co.ohq.ble.entity.internal.UserControlPoint` and `OHQDevice.UserAuthenticatingState` establish consent `02 user codeLo codeHi`, selected-user registration `40 user codeLo codeHi`, and response `20 requestOpcode result`, with `01` success. The recovered default consent code is decimal 526 (`020E`). Matching successful acknowledgment is required; Android bonding is distinct from user registration.

Dedicated time characteristics are Omron `5045 / 2A08` (seven-byte Date Time: uint16 LE year, month/day/hour/minute/second) and SIG `1805 / 2A2B` (the same seven bytes, ISO weekday, fractions256 and adjustment reason). GATT write acknowledgment alone does not verify clock read-back. X7 clock changes belong to the bounded registration stage below.

## Other vendor-memory monitors

The [model comparison](omblepy-comparison.md) records the pinned source, bank starts, record widths and accepted model identities. `VendorMemoryModel` contains independently implemented read maps and decoders. `VendorWlpSession` performs a single bounded full-bank history transaction. The standard BP/Optional Service path retains discovery priority when complete; this memory path handles verified WLP layouts.

Legacy models use service `ecbe3980…`, four indexed RX/TX channels, and control `b305b680…`. RX channels concatenate in characteristic-index order, independently of arrival order. A 24-byte reply normally spans channels 0 and 1. The final channel may contain padding after the declared frame, but missing bytes are never fabricated. Single-channel HEM-7377T1 uses FE4A, OS bonding and no legacy application key. Its 24-byte START response requires negotiated ATT MTU ≥27.

Setup subscribes the first RX channel to allow the peripheral's security request, waits for Android bonding, then subscribes the remaining channels. For legacy models, explicit Pair sends `02` plus sixteen zeros, requires `82 00`, then sends `00` plus a random sixteen-byte key and requires `80 00`. It then unlocks with `01` plus that key and requires `81 00` before enabling history. Connect sends only `01` plus a saved key and requires `81 00`. Key acknowledgments are exactly two bytes in this implementation. The active state-machine permit authorizes only its current issued frame at the actual GATT boundary.

Keys are scoped by monitor address and verified model and encrypted with Android Keystore. A pending key is saved durably before provisioning; retrying Pair reuses it until acknowledgment. Successful acknowledgment promotes it without prematurely destroying the prior saved key. Connect may try the pending and prior key after an exact negative unlock response, but cannot switch to programming. Key bodies and notifications are omitted from diagnostics; disconnect revokes the permit and clears in-memory candidates.

History uses exact START `08 00 00 00 00 10 00 18`, addressed READs of at most sixteen bytes wholly within a mapped bank, and END `08 0F 00 00 00 00 00 07`. START requires a sixteen-byte identity payload; READ requires its exact access/address/count echo, declared size, zero status and valid XOR. END must validate before success. A malformed response fails the session; timeout/disconnect retains previously decoded records. Full-bank mode skips erased slots and does not reset unread counters. All addressed EEPROM writes, vendor clock updates and firmware commands remain blocked for these added models.

## X7 WLP transport

Required FE4A channels:

| Role | UUID |
| --- | --- |
| TX | `db5b55e0-aee7-11e1-965e-0002a5d5c51b` |
| RX | `49123040-aee8-11e1-a74d-0002a5d5c51b` |
| SYS | `b305b680-aee7-11e1-a730-0002a5d5c51b` |

Plain WLP enables RX notifications, then SYS notifications, waits 500 ms and sends exactly 20 SYS bytes: `11 nonce[4] zero[15]`. The nonce is fresh random data for each transaction. The response must begin `91 00 nonce[4] 00`, with at least seven bytes. Status, nonce echo and final flag are checked. Reserved trailing padding is ignored: the tested cuff returned a valid 244-byte padded response. Unrelated messages cannot establish readiness. Disconnect clears readiness and connection-generation guards prevent stale callbacks from continuing setup.

Evidence: `OHQBleDevice.q()` orders RX, SYS, `WAIT_WAKEUP`, transaction and `CONNECTED`; `ohq.a.q4` uses `ohqWaitWakeupDeviceTime`, default 500 ms; `r3` builds `OHQSysCommand.e()`, and `r5` checks the response. `ohq.a.m2` calls `BLEDevice.J(..., 1, ...)`; `BLEDevice.I/J -> L` maps argument 1 to Android `WRITE_TYPE_NO_RESPONSE`. The nonce request and single-channel data use that mode, and characteristic properties must allow it. Cryptographic handshakes and later queued SYS commands use acknowledged writes.

The read-only outgoing WLP frames are:

```text
START  08 00 00 00 00 10 00 18
READ   08 01 00 addressHi addressLo length 00 XOR
END    08 0F 00 00 00 00 00 07
```

Read count is 1–16; access/high-address byte must be zero, and a request cannot cross `0x10000`. XOR covers the frame. `OHQWlCommand.j(true)` / `g(true)` establish START/END. Its `e()` checks response opcode and echoed access/address/count bytes 2–5; `s()` reads status at the second-last byte, and `p()` extracts bytes 6 through length−3. The implementation requires the correct XOR, zero status and exact payload length, with bounded fragment reassembly. Read blocks use 16 bytes when MTU permits the 24-byte reply, otherwise 8. Missing bytes are never synthesized.

The captured successful END response is `08 8F 00 00 00 00 00 87`. Every requested byte plus this final acknowledgment is required for a complete read. The original model has `keep_connection_support=false` and a 3000 ms connection-cancel delay; the tested cuff shut down about 3.2 seconds after END with Android GATT 257. Completed results survive this expected closure. A partial read or merely sending END does not qualify. A fresh transfer requires waking/reconnecting.

Plain Pair intentionally refreshes Android bonding. Static source sequence: `OmronCommLibManager` chooses `OGSCConnectionMode.REGISTRATION`; `ogsc.a.t2` / `OGSCWLConnectMode.a(1)` select lower mode 0; `ohq.e.a()` passes it to `BLEDevice.o()`; `ble.a.o0.a()` saves it and resets retry counter H. `BRANCH_REMOVE_BOND` guard `ble.a.l1.a()` takes removal for mode 0, H=0 even when the ordinary removal flag is false. `ble.a.b1` / `ble.a.o2.t()` remove the bond before new bonding. OpenPressure waits for the new state sequence; stale BONDED broadcasts cannot complete the new attempt. Connect reuses a bond. Android may block hidden-API removal, requiring the user to forget the cuff in system settings.

## X7 model map and records

In the inspected APK, `res/raw/device_category.zip` contains `device_category/0x0000/HEM-7380T1-EOSL/DeviceConfig.sys`, `VitalDataIndexes.json` and `OtherInfo.json`. These supply the independent record map. `OtherInfo.json` specifies byte order 1 and ignored record-checksum blocks 1/2; `ogsc.a.h0.h()` establishes little endian and bit numbering from the least significant bit. `com.omronhealthcare.connectivitylibrary.mappers.DataMapperKt.e()` identifies movement, irregular pulse and the separate AFib field.

| Region | Logical address and bounds |
| --- | --- |
| Settings/index | `[0x0010, 0x0054)`, 68 bytes |
| User 1 BP bank | `0x01C4`, 100 slots × 16 bytes; end `0x0804` |
| User 2 BP bank | `0x0804`, 100 slots × 16 bytes; end `0x0E44` |
| Separate PPW area | Starts `0x010000`, outside exposed 16-bit reads |

`h0.c/w` interprets pointer/full masks; `o6` applies latest-position correction −1. For BP pattern `OF00000011111111`, full is bit 14 and the low byte is the next pointer. With full clear, next pointer is the populated-slot count; with full set all 100 slots are eligible. Invalid pointers fail. A fresh index and its block-3 additive checksum are checked before history reads. `o6.e()` constructs valid slot ranges rather than sweeping holes. `WlpReadSession.appendRange()` extends a completed range before END in the same session, with bounded per-range and aggregate length. Zero-record banks generate no data requests.

| Field | Position in 16-byte record |
| --- | --- |
| Systolic / diastolic / pulse | byte 0 + 25 / byte 1 / byte 2 |
| Year | full byte 3 + 2000 |
| Hour / day / month | LE word at 4: bits 0–4 / 5–9 / 10–13 |
| Irregular pulse / movement | word at 4: bits 14 / 15 |
| Seconds / minutes | LE word at 6: bits 0–5 / 6–11 |
| Cuff / AFib flags | word at 6: bits 12 / 13 |
| Consecutive measurement | word at 6: bits 14–15 |
| Other metadata | byte 8; byte 9 low/high nibbles include artifact/IHB fields |
| Sequence | LE uint16 at bytes 10–11 |

Movement, irregular pulse and AFib are decoded for display; unimplemented metadata remains in the raw record. No mean arterial pressure is invented. An all-FF or invalid slot inside a declared populated range makes the transfer incomplete. Malformed measurements are reported as rejected; invalid dates, including seconds 60–63, remain unavailable. Circular physical slot order is not chronology; records are sorted using valid timestamps while retaining raw sequences and user-bank identity.

The tested cuff returned E3 for address zero and for a read crossing a predicted populated error-log boundary. E3 remains an opaque device error, not a proven universal “unpopulated” or “authentication failed” code. Recovered error-region examples are user 1 `[0x0098,0x00B6)` (3 × 10 bytes), user 2 `[0x00FC,0x0106)` and hardware `[0x0160,0x016A)` in the captured index. These are index-dependent observations, not blanket permission to sweep those ranges. They are not used for ordinary BP history. Unknown addresses are not automatically probed.

## X7 encrypted WLP and authorization

The encrypted path orders SYS then RX subscriptions, nonce verification, application encryption and cuff authorization. `ohq.e.a()` disables explicit Android `createBond()` for custom pairing; `ohq.a.s4` chooses fresh keys for Pair and the stored key for Connect. `ohq.a.s6/t6/u6` send `70 01` (89 bytes), `70 05` and `70 06` (46 bytes) using acknowledged writes. The independent `SecureSession` implements the native format below and wraps the completed LTK using Android Keystore.

After encryption, `ogsc.a.b3` / `OHQSysCommand.c()` send the exact 20-byte authorization poll `20 01 00` plus 17 zero bytes. `ogsc.a.b6` limits it to WLP1COM_PAIR. `OHQSysCommand.j()` checks response result byte 2, and `ogsc.a.e3/c6` interpret `A0 01 00 state`: `00` waiting, `01` complete, `02` rejected. Waiting polls have a total 60-second bound. Unknown states, bad results and truncated replies fail closed. Only explicit COMPLETE plus SYS verification enables reads; a physical-button-specific notification was not established.

Every post-handshake vendor command is checked in plaintext before encryption, then authenticated incoming frames pass integrity, counter and replay checks before parsing. There is no generic encrypted-write escape from the command policy. Disconnect/error revokes readiness. Pairing bodies, challenges and keys are omitted from diagnostics. This path has software tests but no captured hardware interoperability confirmation.

The separate WLP4COM graph has `REGISTERING_USER` / `AUTHENTICATING_USER`, with `ohq.a.e4/g4` sending SYS `00`/`01` plus a supplied 16-byte account token. `UtilityKt.b()` derives an account-email value for that path; it is not copied into X7 plain registration. SYS `30/34/31` configures phone notifications rather than the X7 user-selection stage. These commands remain blocked. Despite its name, inspected `getDeviceDetailsWithKey` through `m4/n4/q3/o4` reads requested Device Information values; its list contains information keys, not proof of a shared device secret.

## Independently recovered STP cryptography

These native wire observations are implemented in Java using platform crypto primitives:

| Routine / virtual address | Behavior |
| --- | --- |
| `Inner::prepare_pair_req`, `0x299a8` | Body `01`, 7 random bytes, 16 random bytes, P-256 public X/Y as 32-byte little-endian coordinates; 88 bytes total |
| `Inner::process_packet_in`, `0x29cc0` | Response `81` exactly 88 bytes; `85` and `86` exactly 45 bytes; rejects non-increasing incoming counters |
| `Inner::process_pair_resp`, `0x29e80` | Reverses peer coordinates and performs P-256 ECDH |
| `Inner::prepare_enc_req`, `0x29bc8` | Body `05`, local challenge 16, SKD contribution 8, IV contribution 4, reserved zeros 16; 45 bytes |
| `Inner::process_enc_resp`, `0x29f68` | Pairing LTK via AES-CMAC, session key from peer SKD then local SKD, encrypted challenge |
| `Inner::process_challenge_resp`, `0x2a168` | Authenticates CCM and compares first 16 plaintext bytes with local challenge |
| `Inner::po`, `0x297ec` | Data `C0`, uint32 LE counter, ciphertext, 8-byte MIC; payload limit 231 |
| `omsec_hal_ccm_encrypt/decrypt`, `0x2a5f8` / `0x2a640` | Four authenticated-data bytes, eight tag bytes |

The GATT wrapper prepends `70` to outgoing handshake bodies and strips response prefix `F0`; `FF` denotes an error. The second response byte is normally native opcode `81`, `85` or `86`, not a universal zero status byte.

With ECDH shared X coordinate `W` as 32 big-endian bytes, 16-byte pairing randoms `Nphone/Ndevice`, and 7-byte random transcript fields `Aphone/Adevice`:

```text
SALT = 6c888391aaf5a53860370bdb5a6083be
T    = AES-CMAC(SALT, W)
LTK  = AES-CMAC(T, 01 || ASCII("btle") || Nphone || Ndevice || Aphone || Adevice || 01 00)
SK   = AES-CMAC(LTK, SKDdevice[8] || SKDphone[8])
```

The seven-byte fields are random in the inspected request builder; they are not assumed to be actual Bluetooth addresses. SALT is a protocol constant, not a distributed pairing secret.

CCM nonce is 13 bytes:

```text
uint32LE(counter) || direction || IVdevice[4] || IVphone[4]
```

Direction is `80` for phone transmission, `00` for device transmission. Tag size is 8, length-field size 2. Authenticated data is the four-byte LE counter. The initial challenge uses counter/authenticated data zero. Challenge plaintext is the peer's 16-byte challenge, 16 zero bytes and 4 fresh random bytes. Normal data starts at counter 1; accepted receive counters must strictly increase.

The inspected Java wrapper applies application encryption only to proprietary `ecbe3980…` / FE4A service characteristics, not standard BP, Current Time or Optional Service characteristics. OpenPressure allows the exact STP handshake but exposes no general STP vendor-command transmit API. Additional link-security requirements remain a hardware question. Standard CMAC/CCM vectors and synthetic fresh-pair/saved-key peers test recovered behavior; they do not replace interoperability testing or independent cryptographic review.

## Automatic-transfer source evidence

The inspected original app watches advertisements for changed per-user sequence metadata and then requests history. Advertisements do not carry the decoded BP values. Source paths in the same hashed APK:

- `com.omronhealthcare.connectivitylibrary.autosync.OmronDeviceAutoSync.m()` schedules `N()`, selecting `AUTO_TRANSFER`, saved serial/user/resume, timeout 0 and a matching scan callback.
- Completion returns through `BaseOmronConnectivity.I()` and the restart callback. `com.omronhealthcare.foresight.modulemanager.connectivitymanager.ConnectivityManager.H0()` supplies 180000 ms normally, 60000 for device type 2 and zero on initial foreground start. This is scanner restart delay, not periodic GATT BP polling.
- `jp.co.omron.healthcare.communicationlibrary.ohq.OHQBleManager.c()` uses the same bank layout for manufacturer types 1/2/6. After removing company ID `020E`, byte 0 is type and byte 1 flags; `(flags & 3)+1` is user count. User i has uint16 LE last sequence at `2+3*i` and stored-record count at `4+3*i`. Type-6 error trailer is separate. Count does not necessarily mean unread count.
- Original scan filtering compares positive advertised sequence with saved resume, rejects streaming mode and has clock/forced-transfer triggers. `ogsc.a.b()` derives last sequence from next-sequence minus one, wrapping zero to 65535.

OpenPressure uses changed sequence/count metadata for saved plain bonded X7 devices, then the bounded read-only index/history/END workflow. It does not reproduce the original app's settings-write triggers. Operational policy is in [automatic imports](automatic-import.md); write restrictions and exact registration source evidence are in [safety](safety.md).

## Independent source comparison

The [pinned omblepy audit](omblepy-comparison.md) compares framing, X7 records, other device maps and settings writes. It confirms the existing X7 layout while distinguishing older WLP unlock/channel workflows implemented separately from X7. Original EOSL evidence takes precedence where the parsers differ.
