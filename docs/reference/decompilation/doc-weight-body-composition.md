# OMRON connect — Weight scales & Body-composition monitors (category 1)

**Transport family:** TWO coexist, chosen per model:
1. **SIG standard GATT** — Weight Scale service **0x181D** / Weight Measurement **0x2A9D**, and Body
   Composition service **0x181B** / Body Composition Measurement **0x2A9C**. Parsed by the
   `jp.co.ohq.ble` SDK (`jp.co.ohq.ble.entity.internal.*`). **This is the primary path for modern
   Bluetooth scales / BCMs** and is fully resolved below.
2. **WLP ("WellnessLink")** — proprietary memory-read command protocol on service
   `ecbe3980-…` for legacy devices (BCM-500, older HBF-*). Framing/opcodes/checksum: see
   `doc-01-common-wlp.md`. Record extraction mechanism below (§4); exact firmware record offsets are a
   documented gap.

See `doc-02-scan-identification.md` for scan/advertisement matching and `doc-00-common-stp.md` for the
AES-CCM layer (only relevant if a scale is a `WLP1COM_PAIR` device).

Markings: **Observed** (ev_ id) / **Inferred** / **Unknown**.

---

## 1. Device identification (which protocol?)

- **Legacy catalogue** `assets/Device/device_models.json`: scales are **`category: 1`**
  (e.g. `BCM-500`/`HBF-222T_Z`, `HBF-228T`, `HBF-255T`, `HBF-702T_AP`, `HN-300T2`…). These entries
  carry UI/pairing metadata only; the protocol is chosen in code from the advertised services / device
  group. **Observed** (JSON).
- **VG/"Voltron" catalogue** `assets/Device/vg_device_models.json`: entries tagged
  `"standard":"WLP"` (e.g. `HEM-6232T-AP`, `HBF-255T_AP`) → legacy WLP command protocol (some over the
  encrypted `WLP1COM_PAIR` variant). **Observed** (JSON).
- **Modern SIG scales:** handled by the `jp.co.ohq.ble` SDK whose own category enum
  `jp.co.ohq.ble.enumerate.OHQDeviceCategory` is `0 Unknown, 1 BloodPressureMonitor, 2 WeightScale,
  3 BodyCompositionMonitor, 4 PulseOximeter, 5 HealthThermometer` (**Observed**, smali clinit). The app
  UI drives these via `com.omronhealthcare.foresight.view.module.pairing.weightscale.*`.

**Rule of thumb (Inferred):** if the device advertises SIG services **0x181D/0x181B**, use the SIG path
(§2/§3). If it exposes the Omron WellnessLink service **`ecbe3980-…`** with the `aee7/aee8` char family,
use WLP (§4). A `WLP1COM_PAIR` device additionally needs the omsec pairing before any WLP command.

---

## 2. SIG Weight Measurement — 0x2A9D (service 0x181D)

**Observed** `jp.co.ohq.ble.entity.internal.WeightMeasurement(byte[], WeightScaleFeature)`
(ev_0e179967). Little-endian uint16 fields (`Bytes.a(buf, off, true)`).

### Flags — byte 0 (`WeightMeasurement.Flag`, ev_1af25d64)

| bit | flag | effect |
|----:|------|--------|
| 0 | ImperialUnit | 0 = SI (kg, m); 1 = Imperial (lb, in) |
| 1 | TimeStampPresent | 7-byte timestamp at offset 3 |
| 2 | UserIDPresent | 1-byte user index |
| 3 | BMIAndHeightPresent | BMI + height fields present |

### Byte layout

| offset | field | type | scaling |
|-------:|-------|------|---------|
| 0 | Flags | u8 | — |
| 1–2 | **Weight** | u16 LE | × 0.005 kg (SI) · × 0.01 lb (Imperial). *Factor overridable by Weight Scale Feature (0x2A9E): `c()`=SI, `d()`=Imperial.* Rounded to 3 dp |
| 3–9 | Timestamp | 7 B | if bit1; `Bytes.h` = year u16 LE, month u8, day u8, hour u8, min u8, sec u8 |
| +0 | User index | u8 | if bit2 (0-based user slot) |
| +0..+1 | **BMI** | u16 LE | if bit3; × 0.1 |
| +2..+3 | **Height** | u16 LE | if bit3; × 0.001 m (SI) · × 0.1 in (Imperial) |

Field order after flags is fixed; offsets shift by whether bit1/bit2 are set (base cursor = 10 if
timestamp present else 3, then +1 per present optional).

---

## 3. SIG Body Composition Measurement — 0x2A9C (service 0x181B)

**Observed** parser `jp.co.ohq.ble.entity.internal.BodyCompositionMeasurement(byte[] p1, byte[] p2,
BodyCompositionFeature)` (inner `1DataParser`, ev_d6f71bcb). **Flags are a uint16 LE** (`Bytes.d`,
offset 0–1). All value fields are u16 LE.

### Flags — bytes 0–1 (`BodyCompositionMeasurement.Flag`, ev_61214426)

| bit | flag |
|----:|------|
| 0 | ImperialUnit (0 = SI kg/m, 1 = Imperial lb/in) |
| 1 | TimeStampPresent |
| 2 | UserIDPresent |
| 3 | BasalMetabolismPresent |
| 4 | MusclePercentagePresent |
| 5 | MuscleMassPresent |
| 6 | FatFreeMassPresent |
| 7 | SoftLeanMassPresent |
| 8 | BodyWaterMassPresent |
| 9 | ImpedancePresent |
| 10 | WeightPresent |
| 11 | HeightPresent |
| 12 | MultiplePacketMeasurement (record continues in a 2nd notification) |

### Byte layout (fields appear in this order, each gated by its flag)

| offset | field | type | scaling / notes |
|-------:|-------|------|-----------------|
| 0–1 | Flags | u16 LE | — |
| 2–3 | **Body Fat %** | u16 LE | × 0.1 → percent (code stores fraction = raw × 0.001). *Always present.* |
| 4–10 | Timestamp | 7 B | if bit1 (year u16 LE, mon, day, hr, min, sec). Cursor → 11, else 4 |
| +1 | User index | u8 | if bit2 |
| +2 | **Basal Metabolism** | u16 LE | if bit3; unit kJ (resolution 1), `Bytes.b` |
| +2 | **Muscle %** | u16 LE | if bit4; × 0.1 → percent (stored fraction raw × 0.001) |
| +2 | **Muscle Mass** | u16 LE | if bit5; × mass-factor (SI 0.005 kg / Imp 0.01 lb) |
| +2 | **Fat-Free Mass** | u16 LE | if bit6; × mass-factor |
| +2 | **Soft Lean Mass** | u16 LE | if bit7; × mass-factor |
| +2 | **Body Water Mass** | u16 LE | if bit8; × mass-factor |
| +2 | **Impedance** | u16 LE | if bit9; × 0.1 Ω (Omron use of this field) |
| +2 | **Weight** | u16 LE | if bit10; × mass-factor |
| +2 | **Height** | u16 LE | if bit11; × 0.001 m (SI) / × 0.1 in (Imp) |

- mass-factor and height-factor come from the **Body Composition Feature** char (`fC`,`fB`; defaults
  SI 0.005 kg / 0.001 m, Imperial 0.01 lb / 0.1 in).
- **Multi-packet reassembly (Observed):** the constructor takes **two byte arrays** and runs the same
  field parser over `p1` then `p2`. When flag bit12 (MultiplePacketMeasurement) is set, the device sends
  the measurement as two 0x2A9C notifications; concatenate-by-parse (parse packet 1, then continue the
  remaining flagged fields from packet 2 into the same object). This matches the SIG C1/C2 split.

> OMRON-derived metrics **visceral fat, body age, resting metabolism (kcal), skeletal-muscle class, BMI
> category** shown in the UI (`assets/DefaultSettings/bcm_display_setting.json`, **Observed**) are
> computed app-side (`ClassificationBodyCompositionCalculator`) from the primary fields above plus the
> user profile (age/height/gender) — they are **not** separate wire fields in 0x2A9C.

### Read mechanism (SIG)
0x2A9D / 0x2A9C are delivered by **indication/notification**; subscribe to the measurement char's CCCD,
then read Current Time / set user. **Inferred**; the `jp.co.ohq.ble` SDK (CBCentralManager-style) drives
the GATT. Exact command sequence not traced here — see `doc-02-scan-identification.md`.

---

## 4. WLP path for legacy scales (BCM-500 / older HBF)

Legacy WellnessLink scales do **not** expose 0x181D/0x181B. Stored measurements live in device memory
and are pulled with WLP **single-access reads** (CMD `0x01`, or `0x03` extended) over the `ecbe3980`
service — full framing in `doc-01-common-wlp.md`. Flow:

1. Connect; if `WLP1COM_PAIR`, complete omsec pairing first (`doc-00-common-stp.md`).
2. `getDeviceDetailsWithKey` (registration / model + capacity).
3. Issue `CMD 0x01` reads against the record region; reassemble RX across the `G` characteristics; strip
   header/BCC via `OHQWlCommand.p()`.
4. Records are parsed by the OGSC layer and surfaced as an address-keyed `Bundle`, which
   `com.omronhealthcare.connectivitylibrary.omron.VitalDataParser.b()` (ev_14ef0344) maps to typed
   lists (`weightDataList`, etc.) using **hex equipment-data address keys** (e.g. `"0030"`, `"0032"`,
   `"0034"`, `"e000xx"`). **Observed** — these keys are the device memory addresses, not SIG offsets.

**Gap (Unknown):** the per-model byte layout of a WLP weight/body-composition record (which address
holds weight vs fat% vs timestamp, and their scaling) is data-driven inside `ogsc` / `OCLEquipmentData`
and the firmware memory map; it was not reduced to a byte table within budget. Practical approach for a
client: dump the record region with `CMD 0x01`/`0x03`, then correlate with known values — or prefer
devices that expose the SIG profile (§2/§3), which is fully specified above.

---

## 5. End-to-end client recipe (modern SIG scale — recommended)

1. **Scan** for advertisements carrying service 0x181D (weight) and/or 0x181B (body comp)
   (see `doc-02-scan-identification.md`).
2. **Connect** + discover; (optionally) read **Weight Scale Feature 0x2A9E** / **Body Composition
   Feature 0x2A9B** to learn measurement resolution (else use defaults: weight 0.005 kg, height 0.001 m).
3. **Set Current Time (0x2A2B)** and select the user slot if the device is multi-user.
4. **Subscribe** (indications) to 0x2A9D and/or 0x2A9C.
5. **Step on the scale.** For each indication parse per §2 / §3; for body-composition watch the
   MultiplePacketMeasurement flag and join the two notifications before finalizing.
6. Derive visceral-fat / body-age / resting-metabolism app-side if needed (not on the wire).

---

## Open gaps (Unknown)
- WLP legacy scale record byte layout (firmware memory map / OGSC address → field mapping).
- Exact SIG command/indication handshake inside `jp.co.ohq.ble` (user-slot selection, Current-Time
  gating) — mechanism inferred, not byte-traced.
- Which specific current models route to SIG vs WLP (requires the scan/advertisement matcher — see
  `doc-02-scan-identification.md`).
- Signedness/edge handling of `Bytes.b` (basal metabolism) and the `SpecialValue` (0xFFFF "not present")
  handling were not individually confirmed.
