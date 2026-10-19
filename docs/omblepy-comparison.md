# omblepy source comparison

Reviewed 2026-10-16 against [userx14/omblepy, commit f323d5a](https://github.com/userx14/omblepy/tree/f323d5ae58693cc737c81c1a1e7ff6ac067c4664). This is a source comparison, not a new hardware test. No omblepy executable source was copied into OpenPressure.

## What its tested devices establish

The upstream [support matrix](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/README.md#omron-device-support-matrix) reports working basic readout for eleven models below. Its pairing, unread-counter and clock support vary by model. Those upstream results apply to omblepy, not automatically to this app.

The per-model drivers specify these distinct record maps. Addresses below are hexadecimal, counts are records per user, and sizes are bytes:

| Model / driver | Record starts | Counts | Size / byte order | OpenPressure vendor-memory path |
| --- | --- | --- | --- | --- |
| [HEM-7150T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7150t.py) | `0098` | 60 | 16 / little | Implemented, hardware-untested here |
| [HEM-7155T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7155t.py) | `0098`, `0458` | 60, 60 | 16 / little | Implemented, hardware-untested here |
| [HEM-7156T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7156t.py) | `0098`, `0458` | 60, 60 | 16 / little | Implemented as one user; `0458` read only by Read all; hardware-untested here |
| [HEM-7322T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7322t.py) | `02AC`, `0824` | 100, 100 | 14 / big | Implemented, hardware-untested here |
| [HEM-7342T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7342t.py) | `0098`, `06D8` | 100, 100 | 16 / little | Implemented, hardware-untested here |
| [HEM-7361T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7361t.py) | `0098`, `06D8` | 100, 100 | 16 / little | Implemented, hardware-untested here |
| [HEM-7377T1](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7377t1.py) | `01CC`, `080C` | 100, 100 | 16 / little | Implemented, hardware-untested here |
| [HEM-7380T1](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7380t1.py) | `01C4`, `0804` | 100, 100 | 16 / little | Matches implemented X7 EOSL layout |
| [HEM-7530T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7530t.py) | `02E8` | 90 | 14 / big | Implemented, hardware-untested here |
| [HEM-7600T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-7600t.py) | `02AC` | 100 | 14 / big | Implemented, hardware-untested here |
| [HEM-6232T](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/deviceSpecific/hem-6232t.py) | `02E8`, `0860` | 100, 100 | 14 / big | Implemented, hardware-untested here |

A shared Bluetooth service does not make those maps interchangeable. OpenPressure's `identifyWlp()` verifies Device Information before selecting X7. The listed additional models select a separate model-bound vendor reader after DIS identity and full channel layout checks. Unknown WLP models remain blocked before memory commands. A device independently exposing supported standard BP characteristics may use that path, but this is not evidence of the vendor-history workflow working on that model.

## X7 agreement and differences

The upstream X7 driver uses FE4A with one receive/transmit channel and disables its legacy unlock/key programming. Its bank starts, capacity, pressure/pulse bytes and movement/IHB bits agree with our X7 map. OpenPressure separately performs the EOSL settings/profile-registration sequence recovered from the original app and confirmed by the user's device; omblepy's OS-bond-only declaration does not replace that sequence.

There are deliberate decoder differences. Upstream masks the year to six bits and clamps seconds to 59. OpenPressure follows the original EOSL full-byte year field and retains unavailable timestamps instead of inventing a valid time. Its separate AFib field also comes from the original model assets. Existing tests cover these cases. The pinned upstream X7 parser additionally references undefined `rawSys` at entry; its current file therefore cannot serve as a runnable decoding oracle without a correction. The upstream tested-readout claim is not a claim that this exact revision was tested.

The pinned omblepy revision has no HEM-7196T1 (M4 / X4 Connect AFib) driver. OpenPressure's experimental support for it takes the 60-slot map from Omron's own configuration ([protocol](protocol.md#hem-7196t1)); the HEM-7196T1-FLE captures that peisenh and userx14 shared in omblepy issue 68 show the encrypted route after an Android bond, not a map.

## Commands and EEPROM safety

The [transport implementation](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/omblepy.py) agrees on START, READ and END framing, big-endian request addresses and XOR checksums. Older drivers use four data channels and a dedicated control-characteristic key-programming/unlock exchange. The original APK's WLP4COM graph sends the same `02`, `00` and `01` control frames for these models; the [protocol reference](protocol.md#other-vendor-memory-monitors) records the ordering and acknowledgment differences. This is different from writing a guessed key into X7 EEPROM. OpenPressure implements this exchange only for the nine identified legacy models. It never sends it to the plain X7 or HEM-7377T1.

The [shared driver](https://github.com/userx14/omblepy/blob/f323d5ae58693cc737c81c1a1e7ff6ac067c4664/sharedDriver.py) writes settings when unread-counter reset or clock sync is requested; ordinary full-history mode reads records. OpenPressure never resets the unread counters. It reads populated X7 ranges from a fresh validated index, and on the legacy maps with an evidenced index it reads the settings/index image and then the populated slots (see [index-driven history](protocol.md#index-driven-history)). It requires exact reply lengths/status/echoes and never substitutes missing bytes. Registration alone permits the bounded, backed-up settings changes described in [safety](safety.md#x7-profile-registration). Arbitrary EEPROM and calibration writes remain unavailable.

The comparison found no reason to change the working X7 map. Release 0.3.0 implements the missing model-bound legacy channel/unlock and history readers independently. Unread-counter writes remain disabled. Vendor clock writes exist only as the explicit [clock-block sync](protocol.md#clock-block-sync), untested on every legacy model, whose layouts come from Omron's own settings writes rather than omblepy; the RS7 driver’s own comment marks its clock offsets uncertain. These implementations have not been validated on additional physical monitors.

## Accepted identities and record handling

The app requires the exact base HEM identity in the table or an enumerated alias below. A marketed name, selected UI hint or arbitrary regional suffix is insufficient. Unsupported identities stop before key or memory commands.

| Base | Additional accepted identities |
| --- | --- |
| HEM-7150T | `HEM-7150T-Z`, `HEM-7150T-CA` |
| HEM-7155T | `HEM-7155T-EBK`, `HEM-7155T-D`, `HEM-7155T_ESL`, `HEM-7155T-ESL`, `HEM-7155T_AP`, `HEM-7155T-AP` |
| HEM-7342T | `HEM-7342T-Z`, `HEM-7342T-CA` |
| HEM-7361T | `HEM-7361T-EBK`, `HEM-7361T_ESL`, `HEM-7361T-E`, `HEM-7361T-D`, `HEM-7361T-AP` |
| HEM-7530T | `HEM-7530T-Z`, `HEM-7530T_E3`, `HEM-7530T-E3`, `HEM-7530T_J3`, `HEM-7530T-J3`, `HEM-7530T_AP3`, `HEM-7530T-AP3`, `HEM-7530T_JT3` |
| HEM-7377T1 | `HEM-7377T1-ZAZ`, using the pinned upstream variant described below |

The older regional groups were cross-checked against the original APK's `res/raw/device_category.zip`, under `device_category/0x0000/`: `DeviceConfig.sys`, `VitalDataIndexes.json` and `OtherInfo.json`. Selected configurations agree on WLS3.0, settings windows, banks/capacities and measurement fields. For byte-swapped maps, original `j6.I()` / `k4.a()` swaps each adjacent byte pair before applying the asset field indices. The APK hash remains recorded in the protocol reference; extracted assets are not distributed.

This comparison also found disagreements that must not be hidden by broad aliases. Original 7156 AP/AAP models have one bank, while the pinned upstream base model has two. Omron's model catalog names the `HEM-7156T_AP` entry "HEM-7156T" (advertised model ID `0x057A`) with one user; the [Omron reference sandbox](omron-sandbox.md#hem-7156t-findings) keeps the second bank as an open finding. Omron's configuration therefore does not confirm a second HEM-7156T bank. OpenPressure imports the base HEM-7156T as a one-user model and reads the pinned map's `0x458` bank only during an explicit Read all, skipping it without failing when the monitor gives no answer or an error status there. Whether a physical base HEM-7156T has that bank is unknown. K4 versions of 7155 have different starts and layout. Regional 7322/7600 assets use six year bits where the base upstream decoders use a full byte; 6232 regional assets reverse the upstream movement/IHB assignment. Those additional regional identities remain blocked. The original 7377-ZAZ asset has a different split map and full-byte year from the upstream BP5360 report; its accepted identity deliberately follows the upstream reported variant, with strict single-channel transport checks and no EEPROM writes. It requires physical validation against the particular firmware; the original assets do not independently confirm that map.

The new decoders use the per-model 14-byte big-endian or 16-byte little-endian fields. RS7 movement/IHB ordering differs from other models. No AFib field is inferred. Erased all-FF slots are skipped; invalid records are retained only in diagnostics and make the import incomplete. Omron's code checks an additive record checksum; OpenPressure applies it on every legacy map the [Omron sandbox](omron-sandbox.md) covers, which excludes HEM-7322T and HEM-7377T1, and on every X7-family map (byte 14 of the 16-byte record). Invalid timestamps on otherwise valid readings remain unavailable instead of being clamped. Stored-memory fingerprints remain stable with an unset clock, so reconnect/retry does not duplicate them.

The BP5360 source also describes both users' records in the second bank: byte 13 distinguishes user 1 (`01`) and user 2 (`FF`). OpenPressure uses that metadata, rejects unknown user markers, and rejects summary-like/calendar-invalid records rather than assigning the whole second bank to user 2. Rejected non-record slots may therefore produce an incomplete-import message while valid measurements remain saved.
