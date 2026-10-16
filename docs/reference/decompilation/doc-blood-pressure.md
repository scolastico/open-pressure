# OMRON connect — Blood Pressure Monitors (category 0)

**Transport family:** **STP** for newer models — custom-pairing/encryption handshake on service `ecbe3980…`/`fe4a` (see `doc-00-common-stp.md`), with the **measurement itself read over the standard SIG Blood Pressure service 0x1810 / characteristic 0x2A35**. Older models use **WLP** (WellnessLink, out of scope here) or plain SIG. Device identification in `doc-02-scan-identification.md`.

**Scope devices (category 0, device_models.json, Observed):** HeartGuide `HEM-6410T-Z` (BP8000), Evolv `HEM-7600T`/BP7000, M4/X4 `HEM-7155T-*`, BP300/7400RELN, HCR-7608T2, etc.

Driver: `ohq.OHQBleStpDevice`. BP service wrapper: `ohq.a.d7`. Parsers: `characteristicAnalyzer.BloodPressureMeasurementAnalyzer` (0x2A35), `BloodPressureFeatureAnalyzer` (0x2A49), `BloodPressureMeasurementFlags`, `BloodPressureMeasurementStatus`.

---

## 1. Full flow: discovery → parsed reading

1. Connect, `requestMtu(243)`, discover services (doc-00 §2). (Observed)
2. If the device exposes service J/K → run the **STP handshake** (doc-00 §3) to establish the encrypted session / bond. HeartGuide and the Intelli-IT BP monitors are STP. (Observed category 0 STP devices)
3. **Enable notifications** on the BP measurement characteristic. The BP wrapper `d7` registers **Service 0x1810 / Char 0x2A35** (`this.f141101a.b(Service.d, Char.v, false, null, null)`). (Observed ev_c200095c — d7 ctor). It also uses **BP Feature 0x2A49** (char `u`) via `BloodPressureFeatureAnalyzer`.
4. **Live measurement:** the device sends 0x2A35 as an **indication**; handler `ohq.a.b7` (ev_7d1e076a) calls `BloodPressureMeasurementAnalyzer.a(bytes)`; when valid it delivers `(feature, measurement)` to the app via `ohq.a.p0` → `OHQBlsMeasurementListener.b(...)` (ev_fbe51f07). App entry point: `OHQBleStpDevice.o(listener)` posts `EVT_BLS_GET_MEASUREMENT` (Observed, StpDevice.o). End-of-measurement: `EVT_BLS_ON_FINISH_MEASUREMENT` via `ohq.a.m0` (ev_857e1716).
5. **Stored records:** see §4 (RACP + GLS_EX).
6. **Time sync** if needed (doc-00 §5).

---

## 2. Live BP measurement — SIG 0x2A35 byte table

`BloodPressureMeasurementAnalyzer.a(byte[])` (Observed ev_396f8a22). Little-endian. All pressures and pulse are **SFLOAT** (IEEE-11073 16-bit: 12-bit signed mantissa + 4-bit signed exponent), decoded by `OHQStpUtilities.g` (Observed ev; sign-extends mantissa if bit11 set, exponent if bit15 set).

| Offset | Len | Field | Present when | Type |
|-------:|----:|-------|--------------|------|
| 0 | 1 | **Flags** | always | uint8 (see below) |
| 1 | 2 | **Systolic** | always | SFLOAT |
| 3 | 2 | **Diastolic** | always | SFLOAT |
| 5 | 2 | **Mean Arterial Pressure (MAP)** | always | SFLOAT |
| 7 | 7 | **Timestamp** | Flags bit1 | Date Time: year u16 LE, month, day, hour, min, sec |
| +0 | 2 | **Pulse rate** | Flags bit2 | SFLOAT |
| +0 | 1 | **User ID** | Flags bit3 | uint8 |
| +0 | 2 | **Measurement Status** | Flags bit4 | uint16 LE |

Offsets after byte 7 are cumulative depending on which optional fields are present (standard SIG packing). Units: if **Flags bit0 = 0** values are **mmHg**, if **1** they are **kPa**.

### Flags (`BloodPressureMeasurementFlags`, Observed ev_0b1977a1)
| Bit | Meaning |
|----:|---------|
| 0 | Units: 0 = mmHg, 1 = kPa |
| 1 | Timestamp present |
| 2 | Pulse rate present |
| 3 | User ID present |
| 4 | Measurement Status present |

### Measurement Status (16-bit, `BloodPressureMeasurementStatus`, Observed ev_d6c78a73)
| Bit(s) | Meaning |
|-------:|---------|
| 0 | Body movement detected |
| 1 | Cuff fit too loose |
| 2 | Irregular pulse detected |
| 3–4 | Pulse rate range (in/above/below) |
| 5 | Measurement position error |

### BP Feature (0x2A49, `BloodPressureFeatureAnalyzer`)
Static capability bitmask (body-movement, cuff-fit, irregular-pulse, pulse-range, measurement-position, multi-bond support). Read once; paired with each measurement when delivered. (Observed: used by d7/p0; exact bits SIG-standard, Inferred.)

---

## 3. SFLOAT decode (reference)

`OHQStpUtilities.g(bytes, LITTLE_ENDIAN)` (Observed):
```
raw   = u16_le(bytes)
mant  = raw & 0x0FFF ; if (mant & 0x0800) mant = -(0x1000 - mant)   // 12-bit signed
exp   = (raw >> 12) & 0x0F ; if (exp & 0x8) exp = -(0x10 - exp)      // 4-bit signed
value = mant * 10^exp
```
Special NaN/Reserved SFLOAT codes (0x07FF etc.) follow IEEE-11073; treat as "not available" (Inferred).

---

## 4. Stored measurements (device memory)

Stored BP on STP devices is retrieved through OMRON's **glucose-extension ("GLS_EX")** framing over the **0x5045 command service** plus the SIG **RACP (0x2A52)** control point. API on `OHQBleStpDevice`:

| Method | Event | Purpose | Marking |
|--------|-------|---------|---------|
| `e(int, listener)` / `a(listener)` | `EVT_GLS_GET_NUMBER_OF_STORED_RECORDS` / `EVT_GLS_EX_GET_NUMBER_OF_STORED_RECORDS` | **RACP count** of stored records | Observed (StpDevice.e, .a) |
| `i(int index, listener)` | `EVT_GLS_EX_GET_MEASUREMENT` | **Fetch stored record by sequence index** | Observed (StpDevice.i) |
| `b(int index, listener)` | `EVT_GLS_GET_MEASUREMENT` | Fetch stored record (plain GLS) | Observed (StpDevice.b) |
| `f(listener)` | `EVT_PLXS_DELETE_STORED_RECORDS` | **Delete all stored records** (RACP delete) | Observed (StpDevice.f) |
| `d(Time)` / `n(Time)` | `EVT_GLS_EX_SET_DATE_TIME` | Clock set (doc-00 §5) | Observed ev_960770d2 |

Guards `f140938g` (BLS service) and `f140942i` (GLS_EX service) must be non-null (device must expose the service) or the call returns `NotHaveService`. (Observed)

**Stored-record addressing:** by **record sequence number** (uint16), RACP/Glucose-style, not raw memory address. (Inferred from the GLS_EX/RACP API and `GlucoseMeasurementAnalyzer`.)

**Stored BP record payload (glucose-ext frame, `GlucoseMeasurementAnalyzer.a`, Observed ev_d419edb5, Inferred mapping for BP):**
| Offset | Len | Field |
|-------:|----:|-------|
| 0 | 1 | Flags |
| 1 | 2 | **Sequence number** (record index, uint16 LE) |
| 3 | 7 | Base time (Date Time) |
| +0 | 2 | Time offset (minutes, int16) — if flag set |
| … | | measurement payload (SFLOAT value + type/location nibbles) |
| … | 2 | sensor/annunciation status — if flag set |

> The GLS_EX stored-record **value field layout specific to BP** (sys/dia/MAP/pulse packing inside the glucose-ext frame) was not fully resolved statically → Open gap. Live readings use the clean 0x2A35 layout in §2.

---

## 5. Multi-user handling

- **Live:** the **User ID** byte (0x2A35 Flags bit3) tags each reading. HeartGuide / dual-user cuffs set it to 1 or 2. (Observed field; `maxUserNo` in the catalog = 2 for multi-user BP models.)
- The device enforces per-user record banks; selecting/registering a user is a device-side action during pairing (catalog keys `allUsersAssignedMessage`, `maxUserNo`). App-level user binding is handled by the OGSC layer, not the BLE driver. (Inferred)
- RACP count/fetch operate on the connected user's bank (Inferred).

---

## 6. BP vs other categories / STP vs plain SIG

- **Category** comes from the device catalog (`device_models.json`: `category=0` ⇒ Blood Pressure). (Observed)
- **BP vs oximeter at the GATT layer:** a BP device exposes **service 0x1810 (char 0x2A35)**; an oximeter exposes **0x1822 (0x2A5E)**. The STP driver only arms the BP path when the BLS service/char is present (`f140938g`). (Observed d7 ctor + StpDevice.o guard)
- **STP vs plain SIG:** if the device also exposes the proprietary **`ecbe3980`/`fe4a`** service, the custom-pairing handshake (doc-00 §3) is **required** before data flows; otherwise it is a plain SIG BP monitor (connect → enable 0x2A35 indications → parse). (Observed encryption gating; Inferred selection)
- **HeartGuide (HEM-6410T-Z / BP8000):** category 0, STP, multi-user (`maxUserNo=2`), identifier `HEM-6410T-Z`, `bluetoothSettingsName=BP8000`. It is a wrist BP + activity watch; BP uses the STP+0x2A35 path described here. (Observed catalog). Activity/watch data uses the Nordic-UART service `6e400001…` (M) — separate channel, out of scope.

---

## Open gaps
- BP-specific field packing inside the GLS_EX stored-record frame (live 0x2A35 is complete; stored value layout partial).
- Whether SIG BP traffic is link-encrypted post-handshake (see doc-00 §4 caveat).
- Exact BP Feature (0x2A49) bit assignments (assumed SIG-standard).
- RACP (0x2A52) operand encoding for count/fetch-by-index/delete (assumed SIG Glucose RACP; not byte-traced).
