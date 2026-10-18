# Protocol reference

This reference describes the independently implemented blood pressure paths. It separates facts recovered by static inspection from captured behavior. The [compatibility guide](compatibility.md) records what has actually been tested. The OpenPressure app includes, links and loads no original executable code, Omron SDK or proprietary native binary. The separate, test-only [Omron sandbox](omron-sandbox.md) can execute a developer-supplied native library locally.

## Source artifacts and traceability

The original supplied [decompilation notes](reference/README.md) remain verbatim. Subsequent inspection used `com.omronhealthcare.omronconnect-2026091010_arm64-v8a.xapk`:

| Artifact | SHA-256 |
| --- | --- |
| Contained `base.apk` | `ce6550f7f9ecb52d488ced25fd421a6390ed5a239761784dc5f7510f088bab9d` |
| Statically inspected `libnativeOmsec.so` | `a81351f72021fc93de7fbba526d4e3bd559a00887907f2967218015ca7f38965` |

Research used JADX class/source inspection, REA Android class inventory and LLVM ARM64 disassembly. Original-app execution was not used for those findings. Function addresses below are virtual addresses in the inspected artifact, not runtime addresses. The native inspection covers the listed STP routines, not every function in the library. Physical results came separately from user reports and supplied diagnostic exports, which are not distributed here.

## Protocol selection

A vendor UUID alone is insufficient to choose STP. WLP also uses `FE4A` / `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` and control characteristic `b305b680-aee7-11e1-a730-0002a5d5c51b`. Discovery checks complete service/characteristic layouts. Known WLP data-bank layouts cannot trigger a speculative STP handshake. Where a complete standard BP/Optional Service path exists it can be used without speculative vendor writes.

X7 memory access additionally requires DIS model `180A / 2A24` to equal `HEM-7380T1`, `HEM-7380T1-EOSL`, `HEM-7380T1-EBK` or `X7 Smart AFib`, plus the expected WLP channels. Substrings and a selected UI model do not authorize it. The exact marketing name was reported by the tested EOSL cuff; EBK is accepted because its original model assets match EOSL ([X7 model map](#x7-model-map-and-records)). Aliases are not evidence that other physical variants were tested, and profile registration remains limited to the EOSL identity and its reported marketing name. Other supported vendor maps require their own strict DIS identity and channel verification, as described below; unknown maps remain rejected.

`OHQBleDevice.m()` selects WLP4COM when the model's `ohqMtuSize` is 16, the library default. Otherwise it selects WLP1COM_PAIR when manufacturer type is 8/9 or a saved application LTK exists, and plain WLP1COM in all other cases. Android `getManufacturerSpecificData(0x020e)` strips the company prefix: type is payload byte 0, corresponding to raw byte 2. `OHQBleManager.c()` recognizes plain types 1/2/3/6 and encrypted types 8/9; their layouts are listed under [automatic-transfer evidence](#automatic-transfer-source-evidence). Missing or unknown data cannot select a new security mode. A saved LTK selects encryption even if the current advertisement reports a plain type. The tested cuff advertised type 6, plain WLP. A saved monitor lacking sufficient metadata is scanned by exact address before resuming.

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

The [model comparison](omblepy-comparison.md) records the pinned source, bank starts, record widths and accepted model identities. `VendorMemoryModel` contains independently implemented read maps and decoders. `VendorWlpSession` performs one bounded read transaction over an ordered list of ranges, each wholly inside the model's settings window or one history bank; further ranges can be appended before END. `VendorMemoryIndex` parses the settings/index image that selects those ranges. The standard BP/Optional Service path retains discovery priority when complete; this memory path handles verified WLP layouts.

Discovery also accepts the `BLEsmart_…` name prefix without regard to letter case, including `BLESmart_…`. This is only a discovery hint: it cannot authorize a memory map or bypass the Device Information model check. The base HEM-7156T uses the same read layout and legacy transport as HEM-7155T, but is imported as a one-user model; the second bank of the pinned map is read only by Read all ([index-driven history](#index-driven-history)). Regional identities with conflicting maps remain blocked.

Legacy models use service `ecbe3980…`, four indexed RX/TX channels, and control `b305b680…`. RX channels concatenate in characteristic-index order, independently of arrival order. A 24-byte reply normally spans channels 0 and 1. The final channel may contain padding after the declared frame, but missing bytes are never fabricated. Single-channel HEM-7377T1 uses FE4A, OS bonding and no legacy application key. Its 24-byte START response requires negotiated ATT MTU ≥27.

Setup subscribes the first RX channel to allow the peripheral's security request, waits for Android bonding, then subscribes the remaining channels. For legacy models, explicit Pair sends `02` plus sixteen zeros and expects `82`, then sends `00` plus a random sixteen-byte key and expects `80`. It then unlocks with `01` plus that key and expects `81` before enabling history. Connect sends only `01` plus a saved key and expects `81`. Each acknowledgment must start with the expected `8x` byte, with a `00` status byte when present; further bytes are ignored, so a one-byte or padded acknowledgment is accepted. The active state-machine permit authorizes only its current issued frame at the actual GATT boundary.

The original app sends the same control frames through its WLP4COM graph. The enabled legacy configurations declare WLS3.0 without an `ohq_parameter` section, so `OHQBleDevice.m()` selects WLP4COM from the default `ohqMtuSize` of 16. `OHQBleDevice` then orders `CONFIRMING_ENCRYPTION` (`ohq.a.d4`: `02` plus sixteen zeros) and branches on pairing mode to `REGISTERING_USER` (`e4`: `00` plus key) or `AUTHENTICATING_USER` (`g4`: `01` plus key), all on `b305b680…`. The original fills the key from `UtilityKt.b()`, an account-email-derived value; OpenPressure uses a random key. Two differences remain. The original sends `02` on every connection and does not follow registration with `01`; OpenPressure follows the pinned omblepy order instead. The original guards `c6`, `a2` and `b6` compare only response byte 0 (`80`, `81`, `82`). OpenPressure follows that and additionally rejects a non-zero second byte. Acceptance of one-byte or padded acknowledgments is covered only by synthetic tests and still needs hardware confirmation.

The [Omron reference sandbox](omron-sandbox.md) replays synthetic HEM-7156T fixtures through Omron Connect 2026.09.03's own communication library. It reproduces the `02` confirmation on every connection and the missing `01` after registration. Unlike OpenPressure's 68-byte index read and populated-slot reads (below), the original then reads the index block at `0x10` (44 bytes) and setting-information block 3 at `0x3C` and reads only the records its index marks as unsent. It rejects a record whose sequence number at offset 10 does not match the index. After the transfer it writes the monitor's memory: the index prefix at `0x54` with the unsent counter cleared, the user's birthday block when it differs from the app profile, and block 3 at `0x80` with the phone clock plus one second and an additive checksum (`OHQWlCommand.k()` frames `len 01 C0 addrHi addrLo count data… 00 XOR`). It then ends with the same `08 0F 00 00 00 00 00 07` as OpenPressure; `08 0F FF FF FF 00 00 F8` is its error variant, sent when a session is cancelled. On Connect, the start frame, record read and decoded values agree with OpenPressure. With the app's pairing flags `0x1210`, the synthetic HEM-7156T Pair session returns settings without reading history; OpenPressure imports history during Pair. OpenPressure writes nothing to these monitors' memory. These are source-derived sandbox observations with synthetic data; they do not establish how a physical HEM-7156T answers.

The same sandbox replays fixtures for HEM-7150T, HEM-7155T, HEM-7342T, HEM-7361T, HEM-7530T, HEM-7600T and HEM-6232T; Omron's code decodes the same values as OpenPressure's maps for all of them. It also shows the integrity checks Omron's code applies; OpenPressure now applies the ones described under [record and index validation](#record-and-index-validation). Omron additionally reads only records its index marks as unsent and checks each record's sequence number against the index; OpenPressure reads every populated slot and does not. On 14-byte maps the index uses the same swapped byte pairs as the records. See the [model findings](omron-sandbox.md#other-legacy-vendor-memory-models).

Keys are scoped by monitor address and verified model and encrypted with Android Keystore. A pending key is saved durably before provisioning; retrying Pair reuses it until acknowledgment. Successful acknowledgment promotes it without prematurely destroying the prior saved key. Connect may try the pending and prior key after a negative unlock response (`81` followed by a non-zero byte, with any padding), but cannot switch to programming. Key bodies and notifications are omitted from diagnostics; disconnect revokes the permit and clears in-memory candidates.

History uses exact START `08 00 00 00 00 10 00 18`, addressed READs wholly within the model's settings window or one mapped bank, and END `08 0F 00 00 00 00 00 07`. A four-channel legacy reply carries at most 64 bytes, 8 of framing and 56 of data, so READs request 1–56 bytes there; single-channel HEM-7377T1 requests at most 16. Record reads use the largest whole number of records that fits: 48 bytes (three records) on 16-byte maps, 56 bytes (four records) on 14-byte maps and 16 bytes on HEM-7377T1. The index image is read in a 56-byte block plus the 12-byte remainder. The outgoing command policy enforces the same per-model limit. START requires a sixteen-byte identity payload; READ requires its exact access/address/count echo, declared size, zero status and valid XOR. A malformed response fails the session; timeout/disconnect retains previously decoded records. Erased all-`FF` slots are skipped, and unread counters are never reset. All addressed EEPROM writes and firmware commands remain blocked for these added models. In the sandbox, Omron's code also issues reads above 16 bytes on these models (44 bytes for the index); no physical monitor has answered a read above 16 bytes from OpenPressure yet.

### Index-driven history

Index support is limited to maps whose index prefix is evidenced: HEM-7150T, HEM-7155T, HEM-7156T, HEM-7342T, HEM-7361T, HEM-7530T and HEM-6232T. HEM-7322T and HEM-7377T1 have no evidenced index prefix, and HEM-7600T records carry no sequence number. HEM-7322T and HEM-7600T therefore always read every slot of the selected banks, and HEM-7377T1 always reads every slot of both shared banks.

On an index-supported model, Import, Pair and automatic imports first read the 68-byte settings/index image at `0x10` (16-byte maps) or `0x260` (HEM-7530T, HEM-6232T). On 14-byte maps the image is un-swapped pair by pair, like the records. Its logical prefix is little-endian, per user *u* (1 or 2):

| Field | Logical offset | Use |
| --- | --- | --- |
| Position | u16 at `2·(u−1)` | Write position; consistency check only |
| Unsent count | u16 at `4+2·(u−1)`, low 15 bits | Informational; never reset |
| Sequence | u32 at `8+4·(u−1)` | Record sequence counter; gives the populated count |

A user's index is trusted when the position is at most the bank capacity and either the sequence equals the position (bank not yet full) or the sequence is at least the capacity (the bank has wrapped and every slot is in use). The populated count is then `min(sequence, capacity)`, and the app appends slots 0 to count−1 of each selected bank as ranges in the same session; an empty bank adds no reads. If any selected user's index is untrusted, the HEM-7530T block-3 checksum fails, or the index read itself fails (no reply within 15 seconds, or a framed, checksummed reply with a non-zero status), the import falls back to every slot of the selected banks in the same session; it never aborts for index reasons. A failure while reading those banks still fails the import. Either way the session then attempts the clock update (manual sessions only) and sends one END, exactly as before. Unread counters and the monitor's memory are never written.

The selection follows the saved **Readings to import** setting (value 1 = user 1, 2 = user 2, 3 = both; default both). A user whose bank the model does not offer is ignored, and a selection left without any offered user means user 1. HEM-7377T1 always reads both banks because they are shared; readings are kept by the user marker in each record. HEM-7156T is treated as a one-user model, as in Omron's configuration: index-driven imports read only bank 1. **Read all** skips the index and reads every slot of the selected banks; on HEM-7156T it then reads the second bank of the pinned omblepy map (`0x458`), which Omron's configuration lacks. When that bank's read goes unanswered for 15 seconds or returns a framed, checksummed reply with a non-zero status, the app keeps the bytes already validated, drops the remaining ranges and proceeds to END without failing. The same reply on any other bank still fails the session. Read all is manual only; automatic imports never use it.

### Record and index validation

On models whose maps the [Omron sandbox](omron-sandbox.md) replayed (HEM-7150T, HEM-7155T, HEM-7156T, HEM-7342T, HEM-7361T, HEM-7530T, HEM-7600T, HEM-6232T), a non-erased record must carry an 8-bit additive checksum: logical byte 14 of a 16-byte record, or byte 12 of a 14-byte record after un-swapping, equals the sum of the bytes before it. A mismatch counts as a rejected slot; other readings remain saved, but the import is incomplete. HEM-7322T and HEM-7377T1, which no fixture covers, keep field checks only. On HEM-7530T, logical index bytes 44–57 (setting-information block 3) must sum to byte 58; a mismatch makes the index untrusted. On HEM-7600T, byte 14 of the sixteen-byte START identity must equal the sum of bytes 0–13, otherwise the session is refused.

These rules come from source inspection and are covered by synthetic tests. In the sandbox, Omron's own code detects the same corrupted record and index bytes in the negative fixtures; Omron then stops, while OpenPressure keeps the other readings or falls back to a full read. That shows agreement with Omron's software, not physical behavior. How real monitors fill the index, whether they answer 56-byte reads, and how a HEM-7156T without a second bank responds have not been observed on hardware.

Pair proceeds directly from successful key setup to history on the same GATT connection. Connect leaves history available for an explicit Import. An [automatic import](automatic-import.md) uses Connect's saved-key unlock, starts history immediately and skips the clock write. After reading all selected ranges, the app attempts phone-local clock sync through a writable SIG Current Time or Omron Date Time characteristic, when available, before issuing its single END. A missing time characteristic skips sync; rejection, start failure or timeout is recoverable and proceeds to END without discarding readings. These dedicated time writes use the existing exact payload rules; EEPROM clock sync is not implemented. The clock result appears in the import summary. An acknowledged END establishes session completion. If the link drops during the optional clock/finalization step after all history has been read, the app reports the imported readings while explicitly stating that END was not acknowledged and, where applicable, clock success is unconfirmed.

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

In the inspected APK, `res/raw/device_category.zip` contains `device_category/0x0000/HEM-7380T1-EOSL/DeviceConfig.sys`, `VitalDataIndexes.json` and `OtherInfo.json`. These supply the independent record map. The `HEM-7380T1-EBK` directory has identical JSON files and a `DeviceConfig.sys` that differs only in its `model_id` line. Other WLD3.0 models in the same archive, such as HEM-7376T1 and HEM-7381T1 through HEM-7388T1, have their own configurations and are not accepted on the X7 path. `OtherInfo.json` specifies byte order 1 and ignored record-checksum blocks 1/2; `ogsc.a.h0.h()` establishes little endian and bit numbering from the least significant bit. `com.omronhealthcare.connectivitylibrary.mappers.DataMapperKt.e()` identifies movement, irregular pulse and the separate AFib field.

| Region | Logical address and bounds |
| --- | --- |
| Settings/index | `[0x0010, 0x0054)`, 68 bytes |
| User 1 BP bank | `0x01C4`, 100 slots × 16 bytes; end `0x0804` |
| User 2 BP bank | `0x0804`, 100 slots × 16 bytes; end `0x0E44` |
| Separate PPW area | Starts `0x010000`, outside exposed 16-bit reads |

`h0.c/w` interprets pointer/full masks; `o6` applies latest-position correction −1. For BP pattern `OF00000011111111`, full is bit 14 and the low byte is the next pointer. With full clear, next pointer is the populated-slot count; with full set all 100 slots are eligible. Invalid pointers fail. A fresh index and its block-3 additive checksum are checked before history reads. `o6.e()` constructs valid slot ranges rather than sweeping holes. `WlpReadSession.appendRange()` extends a completed range before END in the same session, with bounded per-range and aggregate length. Zero-record banks generate no data requests, and only the banks chosen under **Readings to import** are read (default both).

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

The WLP4COM key frames belong to the [legacy four-channel models](#other-vendor-memory-monitors) and are blocked for X7 profiles; they are not part of X7 registration. SYS `30/34/31` configures phone notifications rather than the X7 user-selection stage and also remains blocked. Despite its name, inspected `getDeviceDetailsWithKey` through `m4/n4/q3/o4` reads requested Device Information values; its list contains information keys, not proof of a shared device secret.

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

- `com.omronhealthcare.foresight.modulemanager.connectivitymanager.mappers.DeviceDataMapperKt.c()` enables auto-sync for every newly saved device except categories 6 (pulse oximeter) and 19 (thermometer). `DeviceDataMapperKt.a()` routes device type 2, the WLP blood pressure monitors and scales, to `OmronDeviceAutoSync`; type 1 goes to the HeartGuide manager and other types to `TelehealthAutoSync`.
- `com.omronhealthcare.connectivitylibrary.autosync.OmronDeviceAutoSync.m()` schedules `N()`, selecting `AUTO_TRANSFER`, saved serial/user/resume, timeout 0 and a matching scan callback.
- Completion returns through `BaseOmronConnectivity.I()` and the restart callback. `com.omronhealthcare.foresight.modulemanager.connectivitymanager.ConnectivityManager.H0()` supplies 180000 ms normally, 60000 for device type 2 and zero on initial foreground start. This is scanner restart delay, not periodic GATT BP polling.
- `ogsc.a.b()` builds the filter. One condition matches a positive advertised sequence that differs from the saved next sequence minus one (zero wraps to 65535), an unset clock or a forced transfer; a second condition rejects streaming mode. `OHQBleManager.c()` evaluates it against the layouts below.
- Telehealth models such as HEM-9200T do not use this metadata. `TelehealthCommLibManager.h()` connects directly to the saved address with a 60000 ms wait, and `jp.co.ohq.ble.OHQDeviceManager.f()` removes the parsed `EachUserData` structure from blood pressure scan results.
- `ibeacon_support=true` appears in 146 of the 190 per-model `DeviceConfig.sys` files, but no inspected communication or connectivity class reads it. It is not the automatic-transfer switch.

Offsets follow Android's removal of company ID `020E`: byte 0 is the type and byte 1 the flags. For every type except 3, flag bits are 2 clock unset, 3 pairing, 4 streaming, 5 standard/service-UUID mode and 6 forced transfer, and `(flags & 3)+1` is the user count.

| Type | Per-user data and other fields | Original trigger |
| --- | --- | --- |
| 1, 2, 6 | uint16 LE last sequence at `2+3*i`, stored-record count at `4+3*i`; the type-6 error trailer is separate | Changed positive sequence |
| 3 | No sequences; byte 2 is a result identifier, flag bits 4–5 a guidance mode | Any configured sequence filter matches |
| 8 | Byte 3 category, bytes 4–5 LE model ID, uint16 LE sequence at `6+2*i`; exact length 10 for one user or 13 for two | Changed positive sequence |
| 9 | Byte 4 category, bytes 5–6 LE model ID, uint16 LE sequence at `7+2*i`; exact length `7+2*users` | Changed positive sequence |

Byte 2 of types 8/9 holds additional pairing flags. Record count does not necessarily mean unread count, and types 8/9 carry none. These layouts establish what the original app accepts, not which type a particular firmware emits: only type 6 has been captured, from the tested EOSL cuff.

OpenPressure checkpoints changed sequence/count metadata from types 1/2/6 for saved, bonded X7 and verified vendor-memory monitors, then runs a bounded read-only transfer. Types 3/8/9 are parsed with the exact lengths above for scan details but never start an import: type 3 has no sequences, and types 8/9 lack the record counts used for checkpoints and indicate the encrypted X7 path, whose cuff authorization is not attempted in the background. OpenPressure does not reproduce the original app's settings-write triggers. Operational policy is in [automatic imports](automatic-import.md); write restrictions and exact registration source evidence are in [safety](safety.md).

## Independent source comparison

The [pinned omblepy audit](omblepy-comparison.md) compares framing, X7 records, other device maps and settings writes. It confirms the existing X7 layout while distinguishing older WLP unlock/channel workflows implemented separately from X7. Original EOSL evidence takes precedence where the parsers differ.
