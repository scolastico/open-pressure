# OMRON connect — ECG (categories 16 / 100, plus Omron Complete in category 0)

**Transport family:** ECG in OMRON connect is handled **entirely by the AliveCor/Kardia SDK**, not by an Omron GATT ECG profile. Two physical transports depending on the hardware:
- **Acoustic (phone microphone)** — classic KardiaMobile single-lead.
- **BLE** — Kardia 6L / Band and the Omron "Complete" ECG sensor.

Omron wraps all of this behind `jp.co.omron.healthcare.ecgaclibrary.*` ("ECG AliveCor library"). Output is the AliveCor **.atc** file, decoded by `libkardia_ai_jni.so`.

Static analysis only. Markings: **Observed** / **Inferred** / **Unknown**. See `doc-02-scan-identification.md` for discovery.

---

## 1. Device catalogue (Observed, `assets/assets/Device/ekg_device_models.json`)

| displayName | identifier | deviceType (AliveCor) | category | enabled | transport (Inferred) |
|---|---|---|---|---|---|
| KardiaMobile | `ACKDeviceTypeMobile` | ACKDeviceTypeMobile | 100 | yes | **microphone (acoustic)** |
| KardiaMobile (Omron) | `HCG-8011B-GL` | ACKDeviceTypeMobile | 100 | yes | **microphone (acoustic)** |
| KardiaMobile 6L | `ACKDeviceTypeTriangle` | ACKDeviceTypeTriangle | 100 | no | BLE |
| KardiaMobile 6L (Omron) | `HCG-8060T-E` | ACKDeviceTypeTriangle | 100 | no | BLE |
| OMRON Complete (TM) | `HEM-7530T-Z` | `ACKDeviceTypeSakuraOne` | **0** | yes | BLE (BP+ECG combo) |
| OMRON Complete | `HEM-7530T_E3` | `ACKDeviceTypeSakuraOne` | **0** | yes | BLE (BP+ECG combo) |

`vg_categories.json`: category **16 = ECG** (`voltron_grf_category_name_ecg`). Catalogue `category 100` = Kardia stand-alone ECG; the **Omron Complete** is listed under **category 0 (blood pressure)** because it is a BP monitor whose ECG is an added lead — its BP uses **WLP** (`vg_device_models-16.json`: "Complete", protocolName WLP, modelId 152; `vg_device_models.json` vgCategory 2 HEM-7530T_E3 classifiedDeviceID 0x0000 internalClassifiedDeviceID 0x0251), while its **ECG** is recorded through the AliveCor SDK as `AliveCorDevice.OMRON_COMPLETE`.

---

## 2. AliveCor SDK surface (Observed)

Packages: `com.alivecor.{api,ecg.record,ecg.core,ecgcore,atc,ai,kardia,universal,rest,common}` (231 classes) + Omron façade `jp.co.omron.healthcare.ecgaclibrary.*`.

### 2.1 Device + recording configuration
- `com.alivecor.api.AliveCorDevice` enum (ev_438025fa): **`KARDIA_MOBILE`, `KARDIA_BAND`, `TRIANGLE` (=6L), `OMRON_COMPLETE`, `UNSPECIFIED`**.
- `com.alivecor.api.SampleRate` enum (ev_fd0fbf6f): **125 / 250 / 300 / 500 / 600 Hz** (KardiaMobile single-lead is 300 Hz; **Inferred**).
- `com.alivecor.api.RecordingConfiguration` (ev_7612c5d8): `device`, `leads` (LeadConfiguration), `filterType` (default **`FilterType.ENHANCED`**, ev_fe3e53ec), `mainsFrequency` (`MAINS_FREQUENCY_50Hz` / `60Hz`), `maxDurationSeconds`, `resetDurationSeconds`, `yAxisScaleTypes`, `overrideDeviceName`, `hideTrace`, `hideResultActivity`. Constants `DEFAULT_SAMPLE_RATE_Hz`, `DEFAULT_MAX_DURATION_SECONDS`, `DEFAULT_RESET_DURATION_SECONDS` (primitive values inlined → **Unknown** numeric).
- `com.alivecor.api.RecordingDeviceInfo` (ev_11d92f4a): `device`, `deviceUuid`, `serialNumber`, `firmwareVersion`, `hardwareVersion`, `batteryLevel`, `leadConfiguration` (populated for BLE devices).
- `com.alivecor.api.{AliveCorKitLite, AliveCorEcg, AliveCorDetermination, EcgEvaluation, EkgAnalyzer, LeadConfiguration, KardiaKitConfiguration}`.

### 2.2 Transport evidence
- **Microphone (acoustic)**: `com.alivecor.ecg.record.MicPermissionErrorFragment` (+ `_MembersInjector`) and `AliveCorRecordLiteActivity`, `FragEcgRealtime`, `EcgRealTimeView`. The classic KardiaMobile transmits the ECG as an **FM-modulated ultrasonic signal into the phone microphone** (AliveCor's standard mechanism) → **Inferred** (confirmed by the mic-permission requirement + category-100 Mobile has no BLE service).
- **BLE**: `com.alivecor.ecg.record.{BluetoothOffErrorFragment, BluetoothException, LocationPermissionErrorFragment}` → the 6L / Band / Omron Complete sensors connect over BLE. The AliveCor SDK manages its own BLE GATT internally (not exposed as Omron `BLEUuids`). Exact AliveCor BLE service/char UUIDs are inside the SDK and were **not** decompiled → **Unknown**.

### 2.3 ECG data format — AliveCor `.atc` (Observed, `libkardia_ai_jni.so` JNI)
`ATCReader` / `ATCWriter` are JNI-backed (`libkardia_ai_jni.so`):
- `ATCReader`: `nativeGetECGSamples`, `nativeGetECGSampleCount`, `nativeSampleRate`, `nativeMainsFrequency`, `nativeGetAverageBeat`, `nativeGetAverageBeatSampleCount`, `numLeads`, `resolution`, `flags`, `annotations`/`annotationCount`, `dateRecorded`, `deviceData`, `recorderHardware`/`recorderSoftware`, `recordingUUID`, `phoneModel`/`phoneUUID`, `baselineFiltered`/`enhancedFiltered`/`mainsFiltered`/`notchMainsFiltered`/`readStatus`.
- `ATCWriter`: `nativeWriteHeader`, `nativeWriteSamples`, `nativeWriteAverageBeat`, `writeAnnotations`.
- Signal classes: `com.alivecor.ecgcore.{ECGSignalATC(convertSamplesToMV), ECGSignalMV(resampleSignal), BeatSeries, IntervalSeries, IntervalSet}`.
- AI classification: `com.alivecor.kardia_ai.Classifier.determinationFromFile(...)` + `com.alivecor.ai.*` (afib/normal/unclassified determination).
→ ECG is stored as the **AliveCor ATC container** (samples + sample rate + mains freq + lead count + resolution + annotations + beat/interval series). A client reproducing this would read/write ATC via the same JNI, or re-implement the ATC format.

### 2.4 Omron façade
`jp.co.omron.healthcare.ecgaclibrary.api.ecgaccommunication.EcgACLib` (ev_9a6593ff) + `EcgACLibRecordSettings` (ev_2c510ac3), `EcgACLibEventType`, `EcgACLibScreenType`, `EcgACLibSettingType`, `EcgACLibBpInfo`/`EcgACLibBpError`, `GetEcgRecordingScreenCallback`, `MainFragment`, `NfcAdapterWrapper`. This is a thin Omron wrapper that configures and launches the AliveCor recording UI and relays results + BP info (for the Complete, the BP reading accompanies the ECG). `EcgACLibBpInfo`/`BpError` confirm the Complete combines a BP measurement with the ECG recording in one flow.

---

## 3. Is there an Omron-native BLE ECG GATT? — NUS service `6e400001`

**Observed**: `BLEUuids$Service.M = 6e400001-b5a3-f393-efa9-e50e24dcca9e` with characteristics
`BLEUuids$Characteristic.M2 = 6e4000f0-…`, `N2 = 6e4000f1-…`, `O2 = 6e4000f3-…`.

Findings:
- This is the **Nordic UART service *base* UUID** but with **non-standard** characteristic UUIDs (`…f0/f1/f3`; genuine NUS uses `…0002`/`…0003`). So it is an **Omron-proprietary** service that merely borrows the Nordic 128-bit base.
- The literal appears **exactly once** in the whole app (classes7.dex, inside `BLEUuids`); it is referenced only through the enum constant, and **no measurement analyzer, STP/WLP driver, ECG class, or connectivity mapper in traced code wires it** (0 string-literal references elsewhere; `trace-android-references` on `BLEUuids` outer = 0).
- The `connectivitylibrary` DOES carry ECG-over-BLE scaffolding (`constants.BleEcg`, `BleEcgDataKeys`, `mappers.BleEcgDataMapperKt`, `constants.OMRONDeviceEcgFilterType`) — i.e., the stack is prepared to receive ECG data as BLE notifications. **Inferred**: `M2/N2/O2` is the most plausible home for such a **3-characteristic TX/RX/stream** channel (write-command / notify-data / notify-stream), used by an Omron wearable/BLE-ECG device or for firmware streaming.

**Conclusion (marking):** Owner of the NUS-base service `M` / chars `M2/N2/O2` is **Unknown / Inferred** only. It is **not** the KardiaMobile acoustic path, and it is **not needed** for the Kardia/Complete ECG flows (those go through the AliveCor SDK's own transport). Treat `6e4000f0/f1/f3` as a declared-but-unwired Omron vendor streaming service pending a live capture. (Service `N = 0x5045` with chars `0x504A/504B` is similarly a declared Omron vendor control/DFU endpoint, role Unknown.)

---

## 4. How a client records an ECG (summary)

| Device | Discover | Record transport | Decode |
|---|---|---|---|
| KardiaMobile (HCG-8011B-GL / ACKDeviceTypeMobile) | model pick (no BLE) | **phone microphone**, AliveCor FM acoustic, ~300 Hz | AliveCor SDK → ATC → `libkardia_ai_jni.so` |
| Kardia 6L (HCG-8060T-E / Triangle) *(disabled in this build)* | BLE | AliveCor BLE (SDK-internal UUIDs) | ATC |
| Omron Complete (HEM-7530T) | BLE pairing; BP model via WLP | AliveCor BLE as `OMRON_COMPLETE`; BP via WLP | ATC (ECG) + WLP BP record |

---

## Open gaps
- AliveCor BLE service/characteristic UUIDs (6L / Complete) — inside the AliveCor SDK, **not decompiled**.
- Exact ATC binary layout — defined in `libkardia_ai_jni.so`; accessible only via its JNI (`nativeGetECGSamples` etc.).
- `DEFAULT_SAMPLE_RATE_Hz` / `DEFAULT_MAX_DURATION_SECONDS` numeric values (inlined) — **Unknown**.
- Concrete owner + per-characteristic roles of Omron NUS-base service `6e4000f0/f1/f3` and of `0x5045` (`504A/504B`) — **Unknown** (needs a live scan/connect capture).
