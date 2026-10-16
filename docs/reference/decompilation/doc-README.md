# OMRON connect — device communication guides

How the OMRON connect Android app (`com.omronhealthcare.omronconnect` 2026.09.03) talks to each
measuring device over Bluetooth LE (and, for one thermometer, over audio). Reconstructed by static
analysis only (JADX via REA, ELF symbol inspection, config parsing). The app was never run and no
device was contacted. Every claim in the detail docs is marked **Observed** (with a `ev_` Evidence ID),
**Inferred**, or **Unknown**, and each doc ends with an "Open gaps" list.

## Read in this order

1. [`doc-02-scan-identification.md`](doc-02-scan-identification.md) — how a device is discovered,
   recognized from its advertisement, and matched to a model, category and protocol. Start here: it
   decides which of the protocols below applies.
2. [`doc-00-common-stp.md`](doc-00-common-stp.md) — the shared STP sequence: connect, custom pairing,
   AES-128-CCM encryption, time sync. Used by the blood-pressure and pulse-oximeter devices.
3. [`doc-01-common-wlp.md`](doc-01-common-wlp.md) — the shared WLP ("WellnessLink") command protocol:
   framing, opcodes, checksum, multi-characteristic reassembly. Used by older scales and monitors.

Then the per-device-type guide:

| Device type | Category | Guide | Primary transport |
|---|---|---|---|
| Blood pressure monitors | 0 | [`doc-blood-pressure.md`](doc-blood-pressure.md) | STP handshake + SIG 0x1810/0x2A35; older models WLP |
| Weight & body composition | 1 | [`doc-weight-body-composition.md`](doc-weight-body-composition.md) | SIG 0x181D/0x181B or WLP memory reads |
| Pulse oximeters | 6 | [`doc-pulse-oximeter.md`](doc-pulse-oximeter.md) | STP handshake + SIG 0x1822/0x2A5E |
| Thermometers | 19 | [`doc-thermometer.md`](doc-thermometer.md) | SONIC acoustic (MC-280B-E) or SIG 0x1809 / WLP |
| ECG devices | 16/100 | [`doc-ecg.md`](doc-ecg.md) | AliveCor/Kardia SDK (microphone or BLE) |
| Medication cap (DoseSmart) | 2 | [`doc-smartcap.md`](doc-smartcap.md) | Not recoverable from this build |

## The one-paragraph model

A device advertises standard Bluetooth health services plus, on newer Omron hardware, a proprietary
service `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` (or its 16-bit form `0000fe4a`). The user picks a model
in the app first; the app then matches scan results to that model rather than auto-classifying. After
connecting it raises the MTU, discovers services, and enables notifications. If the proprietary service
is present it runs a custom pairing and encryption handshake (an app-layer re-implementation of
Bluetooth secure pairing, with the crypto inside `libnativeOmsec.so`). Measurements themselves are then
read over the **standard** SIG profile for that device type (blood pressure, oximetry, weight, and so
on), while the proprietary service carries the encrypted control channel and stored-record access. Older
devices instead speak WLP, a command protocol with memory-read opcodes and an XOR checksum. Thermometers
split into a Bluetooth path and an audio ("sonic") path. ECG is delegated entirely to AliveCor's Kardia
SDK.

## Shared black boxes (name the boundary, don't guess past it)

- **`lib/arm64-v8a/libnativeOmsec.so`** — the STP pairing request builders and AES-128-CCM framing
  (nonce, associated data, MIC, packet header). P-256 ECDH + AES-CMAC + AES-128-CCM. The exact minimal
  JNI surface a re-implementation needs is listed in `doc-00-common-stp.md`.
- **`lib/arm64-v8a/libsoniccommunicationlibrary.so`** — the acoustic modem for the MC-280B-E
  thermometer (ASK/PWM demodulation). JNI surface named in `doc-thermometer.md`.
- **`lib/arm64-v8a/libkardia_ai_jni.so`** plus the AliveCor SDK — ECG capture and `.atc` encoding.
  Surface named in `doc-ecg.md`.

## Independently re-verified in assembly

- The STP handshake writes to characteristic **`b305b680-aee7-11e1-a730-0002a5d5c51b`** (`BLEUuids.Characteristic.y2`)
  with a leading opcode byte **`0x70`** before the native request body. Confirmed in the decompiled
  pairing action `ohq.a.s6.a` (`bArr[0] = 112; …I(…, Characteristic.y2, bArr, 0)`).
- The native crypto and JNI symbols (`EccPoint_compute_public_key`, `tc_ccm_config`,
  `tc_aes128_set_encrypt_key`, the eleven `nativeOmsec*` entry points) are present as exported symbols
  in `libnativeOmsec.so`.

## Biggest open gaps across all docs

- The AES-CCM nonce/associated-data/MIC construction and STP packet header layout live only in
  `libnativeOmsec.so`; not extracted.
- Whether the negotiated LTK also becomes the OS link key cannot be determined statically.
- Owners of the proprietary `6e4000f0/f1/f3` (Nordic-base) and `0x5045` (504A/504B) services: declared
  in the UUID table but wired by no traced driver. A live capture would be needed.
- Legacy WLP per-model record byte offsets are data-driven in the OGSC/firmware map.
- SONIC acoustic carrier/baud parameters and the DoseSmart cap's BLE protocol are not in this build.
