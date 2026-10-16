# OMRON connect — Scan & Device Identification (shared layer)

App: `com.omronhealthcare.omronconnect` 2026.09.03. Static analysis only (JADX via REA + ELF).
Markings: **Observed** (ev_ id = REA Evidence JSON in `out/`), **Inferred**, **Unknown**.
This document is referenced by `doc-thermometer.md`, `doc-ecg.md`, `doc-smartcap.md`.

---

## 0. TL;DR for a client implementer

To discover and classify an OMRON connect device you need, in order of reliability:

1. **Advertised service UUID(s)** — tells you the protocol family (SIG profile vs Omron STP vs WLP vs none).
2. **Advertised Local Name** — matched against a per-model allow-list (`bluetoothSettingsName` / `completeDeviceLocalNameList`).
3. **Manufacturer-Specific Data (MSD), Omron company ID `0x020E`** — carries pairing-mode flag, user count and per-user unsynced-record counts (NOT the model number).
4. The **concrete model** (`classifiedDeviceId` / `internalClassifiedDeviceId`) is **read after connecting** (device information), then resolved locally (`vg_device_models*.json`) or via cloud `GET api/models/lookup`.

The app does **not** auto-identify an arbitrary device from its advertisement alone. The user first picks a device/category in the UI; the app then scans filtered to that category and matches advertisements against that model's descriptor.

---

## 1. Two BLE stacks are present

| Stack | Package | Role |
|-------|---------|------|
| **Classic "OHQ"** | `jp.co.ohq.*` (`androidcorebluetooth.CBScanner`, `ble.advertising.ADPayloadParser`, `ble.advertising.MS020EBuilder`, `ble.entity.internal.*`) | low-level GATT + advertisement parser + SIG entity parsers (Observed) |
| **Newer "communicationlibrary"** | `jp.co.omron.healthcare.communicationlibrary.*` (`ble`, `ohq`, `ogsc`, `sonic`) | STP/WLP/SIG drivers, OGSC façade, SONIC acoustic (Observed) |
| **App wrapper** | `com.omronhealthcare.connectivitylibrary.*` + `com.omronhealthcare.foresight.*` | Kotlin API the app calls; `DeviceData`, `OGSCManagerInitializer`, pairing view-models (Observed) |

Both advertisement parsers exist; the neovisionaries library (`com.neovisionaries.bluetooth.ble.advertising.*`: `ADManufacturerSpecific`, `LocalName`, `UUIDs`, `ServiceData`, `MSBuilder`) is bundled and used for AD-structure parsing (**Observed**, dex strings).

---

## 2. Scan entry points

**Observed** (dex log strings): the OGSC façade (`...ogsc.OGSCManager`, wrapped by `connectivitylibrary` / `enums.OGSCScanMode`) exposes:

- `startScanWithCategoryID(categoryId, scanTimeOut=60, recordingCallback, …)` — scan filtered to a **device category** (0 BP, 1 body-composition, 2 SmartCap, 6 oximeter, 16 ECG, 19 thermometer).
- `startScanWithDeviceUID(serialId, …)` — scan for one known device (reconnect by serial).
- Low level: `BLEManager.startScan` / `bleStartScan#Input serialId=…`.

**OGSCScanMode** enum (ev_d533e3a4…): `REGISTRATION`, `DATA_TRANSFER`, `STREAMING`, `UNKNOWN`.
- REGISTRATION = first pairing (device is in pairing/advertising-open mode).
- DATA_TRANSFER = routine sync of a bonded device.
- STREAMING = live data (e.g. ECG/weight live).

**OHQProtocol** enum (ev_25cf128f / clinit): `OHQLIB_PROTOCOL_STP`, `_WLP`, `_WLP1COM`, `_WLP1COM_PAIR`, `_WLP4COM`. This is the per-device transport chosen after identification.

---

## 3. Advertisement structure the app looks at

Parsed by `jp.co.ohq.ble.advertising.ADPayloadParser.a()/b()` (ev_a5ddd43f…) into standard AD structures:

- **Flags** (AD type 0x01)
- **Complete/Shortened Local Name** (0x08/0x09) → matched (see §4)
- **Service UUID lists** (0x02/0x03 16-bit, 0x06/0x07 128-bit) → family selection (§5)
- **Manufacturer-Specific Data** (0x FF) → Omron `0x020E` block (§4.2)

### 3.1 Local-name matching (Observed, dex strings)
Fields/keys seen: `bluetoothSettingsName`, `completeDeviceLocalNameList`, `corruptedDeviceLocalName`, `bleLocalNameCheckAsciiCode`, `attributeLocalName`, `getDeviceLocalNameUserNumberInDevice`.
→ Each model descriptor carries a `bluetoothSettingsName` (the advertised name the user is told to look for). The scanner validates the advertised local name against an allow-list and an ASCII-code sanity check; corrupted names are dropped. The local name can also encode an in-device user number.

### 3.2 Omron Manufacturer-Specific Data — company ID `0x020E` (**Observed**)
Builder/parser: `jp.co.ohq.ble.advertising.MS020EBuilder` (company 0x020E) and `EachUserData` (ev_b956ca69, parse ev_92f4dffc…). `EachUserData.parse(byte[])` layout — the byte array **includes** the 2-byte company ID prefix:

| Offset | Size | Field | Meaning |
|-------:|-----:|-------|---------|
| 0–1 | 2 | Company ID | `0x020E` (little-endian on air: `0E 02`) = OMRON HEALTHCARE |
| 2 | 1 | Data type | must equal `1` ("Each User Data"); else block ignored. (`ENABLE_DATA_TYPE=1`, `DATA_TYPE_INDEX=2`) |
| 3 | 1 | Flags | see below (`FLAGS_INDEX=3`) |
| 4… | 3×N | Per-user records | `N = numberOfUser`, repeated (`USERS_INDEX=4`) |

**Flags byte (offset 3):**
| Bits | Field | Notes |
|------|-------|-------|
| 0–1 | `numberOfUser − 1` | so value 0 → 1 user, 3 → 4 users |
| 2 | `isTimeNotSet` | device clock not yet set |
| 3 | `isPairingMode` | device is open for pairing/registration |
| 5 | `isBluetoothStandardMode` | device is in SIG-standard mode (vs Omron proprietary) |

**Per-user entry (3 bytes each), `EachUserData.User`:**
| Offset (rel) | Size | Field | Encoding |
|-------------:|-----:|-------|----------|
| +0 | 2 | `lastSequenceNumber` | uint16 **little-endian** (`Bytes.b(buf,i,true)`) |
| +2 | 1 | `numberOfRecords` | uint8 — count of stored/unsynced measurements for that user |

**Use:** the MSD does *not* identify the model; it drives auto-sync decisions (is there new data? how many users? is the device in pairing mode? is the clock unset so we must set time?). `isPairingMode` gates REGISTRATION-mode scans. (**Inferred** from field semantics.)

---

## 4. Model resolution (after connect)

The concrete model/variant is obtained **from the connected device**, not the advertisement:

- **Keys:** `classifiedDeviceId`, `internalClassifiedDeviceId`, `deviceGroupIdKey` / `deviceGroupIDKey`, `bluetoothSettingsName` (Observed getters/setters in dex; `LookupKey(classifiedDeviceId=…, internalClassifiedDeviceId=…)`).
- **Local catalogues** (`assets/assets/Device/`):
  - `device_models.json` — 145 models; fields include `category`, `protocolName` (`"SONIC"` | `"WLP"` | `null`), `deviceGroupIDKey` (= category as string), `deviceGroupIncludedGroupIDKey` (sub-group), `bluetoothSettingsName`.
  - `vg_device_models.json` / `vg_device_models-<cat>.json` / `vg_all_device_models.json` — "Voltron/VG" models with `classifiedDeviceID` (e.g. `0x0013`), `internalClassifiedDeviceID` (e.g. `0x0003`), `modelId`, `standard` (`"WLP"`).
  - `device_matrix.json` — capability matrix per device class (HG/BCM/EKG/BP/THERMOMETER/OXIMETER…: which of BP/EKG/WEIGHT/TEMPERATURE/OXYGEN it reports).
  - `ekg_device_models.json` — ECG models, `category` 100 (Kardia) / 0 (Omron Complete), `deviceType` = AliveCor type.
  - `smartcap_model.json` — DoseSmart cap (category 2).
- **Cloud fallback (Observed):** `GET api/models/lookup?classifiedDeviceId=<id>&internalClassifiedDeviceId=<id>` resolves an unknown pair to a model descriptor. Constants holder `com.omronhealthcare.foresight.utility.constants.NewDeviceGroupId`.

---

## 5. Protocol / family decision table

After identification the app picks a driver (**Observed** mapping sources noted):

| If device advertises / descriptor says | Family | Driver | Evidence |
|---|---|---|---|
| `protocolName: "SONIC"` (device_models.json), e.g. MC-280B-E | **Acoustic** (no BLE data channel) | `sonic.*` + `libsoniccommunicationlibrary.so` | ev JSON + see doc-thermometer |
| `protocolName: "WLP"` / `standard:"WLP"` (e.g. HEM-6232T, HEM-7530T, HBF-255T, MC-6810T2) | **WLP** (WellnessLink) | `OHQBleWlpDevice` / `OHQWlCommand` over service family `b305b680…`+`8858eb40…`, XOR checksum | report-ble.md |
| Service **J = `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b`** (and 16-bit **K = `0xFE4A`**) present | **STP** (encrypted) | `OHQBleStpDevice` + `OHQCustomPairing` (AES-128-CCM, P-256 ECDH, libnativeOmsec.so) | report-ble.md §2–3 |
| Only standard SIG service UUIDs (0x1810/1808/181D/1822/1809/…) | **Plain SIG profile** | `ohq.characteristicAnalyzer.*` + `jp.co.ohq.ble.entity.internal.*` | §6 |
| ECG model (category 100 / Omron Complete) | **AliveCor/Kardia SDK** (mic or BLE, handled inside AliveCor libs) | `com.alivecor.*`, `jp.co.omron.healthcare.ecgaclibrary.*`, `libkardia_ai_jni.so` | see doc-ecg |

`isBluetoothStandardMode` (MSD bit5) distinguishes a device running in SIG-standard mode from Omron-proprietary mode.

---

## 6. Advertised service UUID map (full, from `BLEUuids$Service.<clinit>`, ev_2d71e4bb…)

All 16-bit values are `0000XXXX-0000-1000-8000-00805f9b34fb`. These are the services the stack knows; a given device advertises only its own subset.

| Enum | UUID (16-bit) | SIG service |
|---|---|---|
| a | 0x1811 | Alert Notification |
| b | 0x1815 | Automation IO |
| c | 0x180F | Battery |
| d | 0x1810 | **Blood Pressure** |
| e | 0x181B | Body Composition |
| f | 0x181E | Bond Management |
| g | 0x181F | Continuous Glucose Monitoring |
| h | 0x1805 | Current Time |
| i | 0x1818 | Cycling Power |
| j | 0x1816 | Cycling Speed & Cadence |
| k | 0x180A | Device Information |
| l | 0x181A | Environmental Sensing |
| m | 0x1800 | Generic Access |
| n | 0x1801 | Generic Attribute |
| o | 0x1808 | **Glucose** |
| **p** | **0x1809** | **Health Thermometer** (see doc-thermometer) |
| q | 0x180D | Heart Rate |
| r | 0x1823 | (reserved) |
| s | 0x1812 | HID |
| t | 0x1802 | Immediate Alert |
| u | 0x1821 | Indoor Positioning |
| v | 0x1820 | Internet Protocol Support |
| w | 0x1803 | Link Loss |
| x | 0x1819 | Location & Navigation |
| y | 0x1807 | (reserved) |
| z | 0x1825 | Object Transfer |
| A | 0x180E | Phone Alert Status |
| B | 0x1822 | **Pulse Oximeter** |
| C | 0x1806 | Reference Time Update |
| D | 0x1814 | Running Speed & Cadence |
| E | 0x1813 | Scan Parameters |
| F | 0x1824 | Transport Discovery |
| G | 0x1804 | Tx Power |
| H | 0x181C | User Data |
| I | 0x181D | **Weight Scale** |
| **J** | `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` | **Omron proprietary STP** (encrypted) |
| **K** | 0xFE4A | Omron 16-bit member service (also STP/AES-CCM) |
| **L** | `aee957f2-0d93-4bc2-abcb-a9363b5f40f5` | Omron vendor (role Unknown) |
| **M** | `6e400001-b5a3-f393-efa9-e50e24dcca9e` | Nordic-UART **base** UUID but with **custom** chars `6e4000f0/f1/f3` (M2/N2/O2) — Omron-proprietary; see doc-ecg §NUS |
| **N** | 0x5045 ("PE") | Omron vendor control/DFU (chars 0x504A/504B); role Unknown |

> Note on M: real Nordic UART uses chars `6e400002`(RX)/`6e400003`(TX). Omron reused only the **service base** `6e400001` and defined its own `…f0/f1/f3`. The literal appears **only** in `BLEUuids` (one occurrence, classes7.dex) and is referenced solely via the enum constant; no analyzer/driver in traced code wires it. Owner **Unknown** (see doc-ecg).

---

## 7. Identification decision table (practical)

| Observed on air | Likely device | Then |
|---|---|---|
| MSD company `0x020E`, `isPairingMode=1`, local name in BP allow-list, service 0x1810 or J | Omron BP (SIG or STP) | connect, read model, pick SIG-BP / STP driver |
| MSD `0x020E`, service 0x181D/0x181B | Omron scale / body-composition | SIG Weight/BodyComp or WLP |
| MSD `0x020E`, service 0x1809 + 0x2A1C char | Omron BLE thermometer (SIG HTS) | SIG thermometer parse |
| MSD `0x020E`, service 0x1822 | Omron oximeter (HPO-300T/200T3) | SIG PLX / STP |
| local name matches MC-280B-E, **no useful BLE data service** | SONIC acoustic thermometer | switch to microphone/speaker acoustic path |
| local name/model = KardiaMobile (HCG-8011B-GL) | AliveCor acoustic ECG | microphone path (AliveCor SDK) |
| model = Omron Complete (HEM-7530T) | BP via WLP + ECG via AliveCor SDK | two sub-flows |

---

## Open gaps
- `MS020EBuilder.create(...)` body not recovered (overload) — the **outbound** MSD build (if the phone ever advertises) is unconfirmed; the **inbound** parse above is Observed.
- Exact Android `ScanFilter` construction inside `CBScanner` (whether it filters at controller level by service UUID / MSD or does software matching) not decompiled — strings confirm category/serial-scoped scanning.
- `OHQMsdFilterKeys` string constant values are inlined (0 refs) and were not individually recovered; their semantics are covered by the `EachUserData` layout above.
- Data-type values other than `1` in the Omron MSD (if any) Unknown.
