# OMRON connect — Thermometer (device category 19)

**Transport family:** two completely different paths, selected by `device_models.json → protocolName`:
- **SONIC** = acoustic (audio) modem, no BLE data channel — model **MC-280B-E** "Eco Temp Intelli IT".
- **WLP** = BLE WellnessLink command protocol — model **MC-6810T2-J** "Eco Temp Intelli IT".
- A generic **SIG Health Thermometer (0x1809)** parser also exists in the stack (used for standard BLE thermometers / Omron devices running in SIG-standard mode).

Static analysis only. Markings: **Observed** / **Inferred** / **Unknown**. See `doc-02-scan-identification.md` for how a thermometer is discovered and classified.

---

## 1. Catalogue facts (Observed, `assets/assets/Device/device_models.json`, category 19)

| Model | protocolName | deviceGroupIDKey | deviceGroupIncludedGroupIDKey | VG ids |
|-------|--------------|------------------|-------------------------------|--------|
| MC-280B-E | `SONIC` | 19 | 640 | — |
| MC-6810T2-J | `WLP` | 19 | 3 | `vg_device_models-19.json`: modelId 417, classifiedDeviceId 19, internalClassifiedDeviceId 3 (also `vg_device_models.json` vgCategory 5: classifiedDeviceID `0x0013`, internalClassifiedDeviceID `0x0003`) |

`vg_categories.json`: category **19 = thermometer** (`voltron_grf_category_name_thermometer`).

---

## 2. SONIC acoustic path (MC-280B-E)

### 2.1 Overview (Inferred from symbols + Java flow)
The thermometer plays an **audio tone burst** through its buzzer; the phone records it with `AudioRecord`; a native DSP demodulates the audio to a byte frame; Java deserializes the frame into a `ThermometerData` measurement. There is **no BLE** for this model.

### 2.2 Native library — `libsoniccommunicationlibrary.so` (arm64)
**Observed** exported JNI symbols (all on `…sonic.protocol.thermometer.ThermometerProtocolParser`):

| JNI symbol (Java method) | Role (Inferred) |
|---|---|
| `nativeInitialize` | create native parser, returns handle (stored in `ThermometerProtocolParser.f141654b : long`) |
| `nativeProcess` | feed audio/PCM, run demodulation, return decoded frame/length (native, no Java body) |
| `nativeGetSignalLevel` | current input signal level (for the "hold near device" UI) |
| `nativeGetTrackingData` | returns `double[]` telemetry: `[0]`,`[1]` + optional extra array (ev_686475a2) |
| `nativeSetLogPath` | debug log path (ev_4e679f9a) |
| `nativeShutdown` | free handle |
| `nativeLeakCheck` | debug |

**Observed** internal C++ symbols / demangled classes (the modem itself — a black box):
`AskDemodulation`, `PwmDemodulation`, `FrequencyEstimator`, `SignalDemodulated`, `SignalSet`, `AbstractStream`/`AsyncStream<Array<SignalDemodulated>>`.
→ The acoustic scheme is **ASK (amplitude-shift keying)** and/or **PWM** demodulation with a **frequency estimator**, running as a streaming pipeline. Exact carrier frequency, baud, preamble and FEC live only in `.rodata`/code of `libsoniccommunicationlibrary.so` → **Unknown** (not extracted).

### 2.3 Audio capture (Observed, dex strings)
`AudioRecord` is used (strings: `"Starting AudioRecord with sample buffer length: "`, `AudioRecord.getMinBufferSize`, `AudioRecord.setPositionNotificationPeriod`, `AudioFormat[sampleRate=…`, `AudioRecord build error. source=`). The exact sample rate is a constant not recovered; **Inferred** a single fixed rate (typical for such modems 44.1/48 kHz). The app-side trigger is `com.omronhealthcare.foresight.utility.SonicThermometerUtils.a(DeviceData)` (ev_5c641e51) which gates the SONIC path:
```java
return deviceData != null && deviceData.getCategory() == 19
        && "SONIC".equals(deviceData.getProtocolName());
```

### 2.4 Java driver flow (Observed)
`sonic.thermometer.ThermometerReceiver` (singleton, ev_14b53931): holds a `ThermometerProtocolParser`, exposes `d()` → `parser.b()` (signal level), `e(int)` (set a param, e.g. user), `c()` getInstance.
`ThermometerProtocolParser.putNextInternal(byte[])` (ev_4e679f9a):
```java
private int[] putNextInternal(byte[] bArr) {
    return c(sonic.c.b.a(bArr, new ThermometerData()));  // deserialize demod frame
}
```
`ThermometerProtocolParser.c()` (ev_686475a2) pulls `nativeGetTrackingData(handle)` → fills `TrackingData.Sonic.ThermometerSignal{ f141694a, f141695b, f141696c[] }` (signal diagnostics, not the measurement).

### 2.5 Demodulated measurement frame → `ThermometerData` (Observed)
Frame deserializer `sonic.c.b.a(byte[], ThermometerData)` (ev_333f33f2):
```java
ByteBuffer bb = ByteBuffer.wrap(bArr);
bb.order(bb.get() == 0 ? BIG_ENDIAN : LITTLE_ENDIAN);  // byte[0] = endianness selector
data.a(bb);                                             // ThermometerData.a(ByteBuffer)
```
`ThermometerData.a(ByteBuffer)` (ev_32b802e6):

| Order | Size | Field | Meaning (Inferred) |
|------:|-----:|-------|--------------------|
| byte 0 | 1 | endianness | `0` → big-endian for the rest; else little-endian |
| +1 | 4 | `f141660a : int` | header/record field (type or status) |
| +1 | 4 | `f141661b : int` | header/record field (count or model) |
| +1 | 8 | timestamp | `long` **epoch seconds**; stored as `×1000` ms into a `Calendar` |
| +1 | 16×N | `VitalData[]` | repeated, each = 4×int32 |

`VitalData` (ev_5e924c6994, fields `f141561a..d`, all `int`): 4 integers per vital entry — **Inferred** `(dataType/kind, value, unit-or-scale, status/subtype)`; for a thermometer the temperature value is one of these ints (scaled integer, e.g. centi-°C). Exact field semantics **Unknown** (no getter names recovered).

> Implementer note: the demod frame is self-describing in endianness (first byte) and is NOT the on-air acoustic waveform — you must reproduce the ASK/PWM demodulation (in the .so) to obtain these bytes. Reproducing the acoustic modem from scratch requires the carrier/baud parameters that are not in the Java layer.

---

## 3. WLP BLE path (MC-6810T2-J)

**Observed**: `device_models.json` / `vg_device_models.json` tag MC-6810T2 `protocolName/standard = "WLP"`, classifiedDeviceId `0x0013`, internalClassifiedDeviceId `0x0003`.
Driver = `OHQBleWlpDevice` / `OHQWlCommand` (same WellnessLink protocol used by HEM-6232T, HEM-7530T, HBF-255T): command framing over the Omron WLP characteristic family (`b305b680…` + `8858eb40…`) with a trailing **XOR checksum** (`OHQWlCommand.a`, ev_1bbd5cb9 — see report-ble.md §1). Temperature records are read as WLP vital records; byte-level WLP record layout for temperature was **not** separately decompiled → **Unknown** (Open gap).

---

## 4. SIG Health Thermometer path (standard / SIG-standard mode)

**Observed**: Service **p = 0x1809** (Health Thermometer) is in the stack's service table (`BLEUuids$Service`), and the following characteristic UUIDs are present in the dex:
- `0x2A1C` Temperature Measurement
- `0x2A1D` Temperature Type
- `0x2A1E` Intermediate Temperature

Parser entity: `jp.co.ohq.ble.entity.internal.TemperatureMeasurement` (ev_9a6593ff) with inner `TemperatureMeasurementFlags`. This is the standard SIG HTS format (indication on 0x2A1C):

| Offset | Field | Encoding (SIG standard) |
|--------|-------|-------------------------|
| 0 | Flags | bit0 units (0=°C,1=°F), bit1 timestamp present, bit2 temperature type present |
| 1–4 | Temperature | IEEE-11073 **FLOAT** (32-bit) |
| +7 | Timestamp | if bit1 (year u16 LE, mon, day, hr, min, sec) |
| +1 | Temperature Type | if bit2 (body location enum) |

(The individual getters `TemperatureMeasurement.a()..d()` are obfuscated; `a()` is a string getter. The SIG format above is the documented standard that this entity parses; field-by-field confirmation = **Inferred**.)

---

## Open gaps
- SONIC acoustic carrier frequency, baud rate, preamble/sync, error-correction and exact PCM sample rate — inside `libsoniccommunicationlibrary.so` only; **not extracted**.
- `VitalData` four-int semantics (which int is the temperature, scale factor, unit) — **Unknown**; recover by capturing a real decoded frame or decompiling the .so's serializer.
- WLP temperature record byte layout for MC-6810T2 — **Unknown**.
- Whether MC-280B-E also exposes any BLE at all (it appears acoustic-only) — **Inferred** acoustic-only.
