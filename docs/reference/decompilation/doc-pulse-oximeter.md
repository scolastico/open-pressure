# OMRON connect — Pulse Oximeters (category 6)

**Transport family:** **STP** — custom-pairing/encryption handshake on service `ecbe3980…`/`fe4a` (see `doc-00-common-stp.md`), with the **measurement read over the standard SIG Pulse-Oximeter Service 0x1822**, spot-check characteristic **0x2A5E**. Device identification in `doc-02-scan-identification.md`.

**Scope devices (category 6, device_models.json, Observed):** `HPO-300T` (displayName "P300 Intelli IT", bluetoothSettingsName "HPO-300T"), `HPO-200T3` (identifier HPO-200T3-J).

Driver: `ohq.OHQBleStpDevice`. PLX service wrapper: `ohq.a.v7`. Parsers: `characteristicAnalyzer.PulseOximeterSpotCheckMeasurementAnalyzer` (0x2A5E), `PulseOximeterFeaturesAnalyzer` (0x2A60), `PulseOximeterSpotCheckMeasurementFlags`, `PulseOximeterSpotCheckMeasurementStatus`.

Relevant characteristic enum letters (Observed): `v1` = 0x2A5E (PLX Spot-Check), `t1` = 0x2A5F (PLX Continuous), `u1` = 0x2A60 (PLX Features), `D1` = 0x2A52 (RACP), Service `B` = 0x1822.

---

## 1. Full flow: discovery → parsed reading

1. Connect, `requestMtu(243)`, discover services (doc-00 §2). (Observed)
2. If the device exposes service J/K → run the **STP handshake** (doc-00 §3). P300/HPO-300T are STP. (Observed category 6 STP)
3. **Enable notifications/indications** on the PLX measurement characteristic. Wrapper `v7` (ctor ev — `jp…ohq.a.v7`) loops `i7 = 0..1` calling `e(false)` to **select the available PLX characteristic** (spot-check 0x2A5E vs continuous 0x2A5F) and arm its CCCD. (Observed v7 ctor)
4. **Live measurement:** device sends **0x2A5E (PLX Spot-Check)** as an indication; handler `ohq.a.s7` (ev_f088d628) implements `ohq.a.o.a(byte[], boolean)`, calls `PulseOximeterSpotCheckMeasurementAnalyzer.a(bytes)`, and delivers `(features, measurement)` via `ohq.a.j1` → `OHQPlxsMeasurementListener.b(...)` (ev_2dacbe44). End-of-measurement: `EVT_PLXS_ON_FINISH_MEASUREMENT` via `ohq.a.h1` (ev_56000b9b).
5. **Stored records / count / delete:** see §4.
6. **Time sync** if needed (doc-00 §5).

### Stored-record de-duplication (Observed, s7 ev_f088d628)
When the measurement-status "validity" path is taken, `s7` keeps a list (`f141346n`) of raw record byte arrays and **drops duplicates** (`Arrays.equals`) before delivering — relevant when replaying stored records. It also gates on `PulseOximeterFeaturesAnalyzer` "measurement status support" (`features bit … == 1`) and the measurement-status `f141505b` field being non-zero. (Observed)

---

## 2. Live PLX Spot-Check measurement — SIG 0x2A5E byte table

`PulseOximeterSpotCheckMeasurementAnalyzer.a(byte[])` (Observed ev_1e87a7c0). Little-endian; SpO2/PR/PAI are **SFLOAT** (see doc-blood-pressure §3 for the decode).

| Offset | Len | Field | Present when | Type |
|-------:|----:|-------|--------------|------|
| 0 | 1 | **Flags** | always | uint8 (see below) |
| 1 | 2 | **SpO2 (%)** | always | SFLOAT |
| 3 | 2 | **Pulse Rate (bpm)** | always | SFLOAT |
| 5 | 7 | **Timestamp** | Flags bit0 | Date Time: year u16 LE, month, day, hour, min, sec |
| next | 2 | **Measurement Status** | Flags bit1 | uint16 LE |
| next | 3 | **Device & Sensor Status** | Flags bit2 | uint24 LE (zero-padded to uint32 in code) |
| next | 2 | **Pulse Amplitude Index** | Flags bit3 | SFLOAT |

Offsets after the fixed 5 bytes advance only for the optional fields that are present (code tracks a running index `i6`, starting at 5, bumped to 12 after the timestamp, then +2 / +3 / +2). (Observed ev_1e87a7c0)

### Flags (`PulseOximeterSpotCheckMeasurementFlags`, Observed ev_f6fac55a; fields f141499a..f141503e)
| Bit | Field | Meaning |
|----:|-------|---------|
| 0 | f141499a | Timestamp present |
| 1 | f141500b | Measurement Status present |
| 2 | f141501c | Device & Sensor Status present |
| 3 | f141502d | Pulse Amplitude Index present |
| 4 | f141503e | Device clock not set (SIG-standard) — Inferred |

### Measurement Status (16-bit, `PulseOximeterSpotCheckMeasurementStatus`)
Field `f141505b` is read by the driver to decide record validity (non-zero → valid completed measurement in the stored-record path). Bit meanings follow SIG PLX "Measurement Status" (measurement ongoing / early estimated data / validated / fully qualified / data from measurement storage / data for demonstration / data for testing). (Inferred — SIG-standard; only the validity gating is Observed in s7.)

### Device & Sensor Status (24-bit)
Decoded as uint24 LE padded to uint32 (`OHQStpUtilities.j` with a 4th zero byte). Bits follow SIG PLX (extended display update ongoing / equipment malfunction / signal processing irregularity / inadequate signal / poor signal / low perfusion / erratic signal / nonpulsatile / questionable pulse / signal analysis ongoing / sensor interference / sensor unconnected / unknown sensor / sensor displaced / sensor malfunction / sensor disconnected). (Inferred — SIG-standard.)

### PLX Features (0x2A60, `PulseOximeterFeaturesAnalyzer`)
Static supported-features bitmask (`PulseOximeterSupportedFeatures`); field `f141506a` = "Measurement Status support". Read once and paired with each measurement. (Observed: used by s7/j1; exact bits SIG-standard.)

---

## 3. Stored measurements (device memory)

PLX stored-record API on `OHQBleStpDevice`:

| Method | Event | Purpose | Marking |
|--------|-------|---------|---------|
| `m(listener)` | `EVT_PLXS_GET_NUMBER_OF_STORED_RECORDS` | **RACP count** | Observed (StpDevice.m) |
| `h(listener)` / `i(index)` | `EVT_PLXS_GET_MEASUREMENT` / `EVT_GLS_EX_GET_MEASUREMENT` | **Fetch stored spot-check record(s)** | Observed (StpDevice.h/i) |
| `f(listener)` | `EVT_PLXS_DELETE_STORED_RECORDS` | **Delete all stored records** (RACP) | Observed (StpDevice.f) |
| `d(Time)` | `EVT_GLS_EX_SET_DATE_TIME` | Clock set (doc-00 §5) | Observed ev_960770d2 |

Requires `f140944j` (PLXS service) non-null or returns `NotHaveService`. (Observed StpDevice.f/m)

Stored spot-check records are the **same 0x2A5E payload** as live (§2), replayed over the notification path, de-duplicated by `s7`. RACP control = **0x2A52 (D1)**; record addressing by sequence number (Inferred, SIG PLX RACP). The delete event name (`EVT_PLXS_DELETE_STORED_RECORDS`) is reused by the BP driver too — it is the generic RACP "delete all stored records" op. (Observed)

---

## 4. Multi-user handling

Pulse oximeters are effectively **single-user**: 0x2A5E carries **no User-ID field** (unlike BP 0x2A35). Records are stored in one bank and distinguished only by timestamp/sequence. (Observed: no user field in the 0x2A5E parse.) HPO-300T/HPO-200T3 catalog entries have no multi-user flag. (Observed)

---

## 5. Oximeter vs BP / STP vs plain SIG

- **Category** from `device_models.json` (`category=6` ⇒ Pulse Oximeter). (Observed)
- **At the GATT layer:** an oximeter exposes **service 0x1822** with **0x2A5E** (spot-check) and/or **0x2A5F** (continuous) + features 0x2A60; a BP monitor exposes 0x1810/0x2A35. The STP driver arms the PLX path only when the PLXS service is present (`f140944j`), selecting 0x2A5E vs 0x2A5F at subscribe time. (Observed v7 ctor + StpDevice.m/f guards)
- **STP vs plain SIG:** presence of the proprietary **`ecbe3980`/`fe4a`** service means the custom-pairing handshake (doc-00 §3) is required before measurements flow; otherwise it is a plain SIG PLX sensor (connect → enable 0x2A5E indications → parse). The P300 Intelli IT / HPO-300T are STP (encrypted pairing). (Observed encryption gating; Inferred selection)

---

## Open gaps
- Exact PLX Measurement-Status / Device-&-Sensor-Status bit semantics (assumed SIG-standard; only the validity gate is byte-confirmed).
- Continuous-measurement (0x2A5F) parse path — `v7` can select it, but no dedicated continuous analyzer was traced (spot-check 0x2A5E is the measured path).
- RACP (0x2A52) operand encoding for PLX count/fetch/delete (assumed SIG PLX RACP; not byte-traced).
- Whether SIG PLX traffic is link-encrypted post-handshake (see doc-00 §4 caveat).
