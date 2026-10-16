# OMRON connect — SmartCap / DoseSmart (device category 2, BP6001)

**Transport family:** Unknown at byte level. The SmartCap ("DoseSmart" medication-adherence cap) is present in the app mainly as **pairing UI metadata + a cloud ordering/fulfilment integration**; no dedicated BLE GATT driver or service/characteristic layout for it is recoverable by static analysis.

Static analysis only. Markings: **Observed** / **Inferred** / **Unknown**. See `doc-02-scan-identification.md` for discovery.

---

## 1. Catalogue facts (Observed, `assets/assets/Device/smartcap_model.json`)

Single model:
- `category`: **2**
- `identifier` / `name` / `displayName`: `DoseSmart` / `Dosesmart` / **"DoseSmart"**
- `isPrimary`: false, `priorityOfTransfer`: 1, `noOfUsers`: 1
- `bluetoothSettingsName`: `""`, `deviceGroupIDKey`: `""`, `deviceGroupIncludedGroupIDKey`: `""` — i.e. **no BLE local-name pattern and no device-group key** are provided (unlike every Omron measurement device). Identification does not rely on an Omron `0x020E` advertisement match.
- Reconnect/repair UI strings present (`smart_cap_reconnecting_message`, etc.) → it does maintain a persistent BLE connection, but the matching keys are empty.

The hardware model code **BP6001** (from the task brief / device list) is associated with this category; the string `BP6001` was **not** found anywhere in the dex or assets (**Unknown** whether it appears at runtime from the cloud).

---

## 2. App integration (Observed)

- UI/config: `com.omronhealthcare.foresight.utility.parser.SmartCapConfigParser` + `com.omronhealthcare.foresight.utility.parser.models.SmartCap` (a Kotlin data/Parcelable POJO mirroring `smartcap_model.json`: displayName, pairing/reconnect/repair message keys, category, enabled, images).
- Persistence: `com.omronhealthcare.foresight.entities.devices.SmartCapDevice` (Realm entity).
- **Cloud (Observed):** `com.omronhealthcare.foresight.cloudsyncmanager.api.smartcap.AddressVerifyApi` and `…api.smartcap.PlaceOrderApiCall`, plus `ItemSmartCapVerifyAddressBinding`. → The SmartCap feature's substantive logic is **ordering/refilling the physical cap** (address verification + place order), not a rich measurement protocol.

## 3. BLE protocol

- **Unknown.** No SmartCap-specific `BLEService`/`BLECharacteristic` UUIDs, no dedicated GATT driver class, and no measurement-record parser were found for category 2.
- It is **not** served by the SONIC, WLP, or STP drivers (those are keyed to categories 0/1/6/19 and to Omron protocolName/service markers).
- **Inferred**: the cap either (a) uses a thin/standard BLE connection whose adherence events (cap opened / dose taken) are handled through a generic connectivity path or a component not distinguishable statically, or (b) is primarily a cloud-centric product in this app with minimal on-device BLE. Given the empty `bluetoothSettingsName`/`deviceGroupIDKey` and the dominant ordering APIs, the measurement/eventing protocol is not implemented in recoverable Omron code here.

---

## Open gaps
- SmartCap/DoseSmart BLE service + characteristic UUIDs, advertisement signature, and dose-event record format — **Unknown** (no static evidence).
- Mapping of model code **BP6001** to the `DoseSmart` descriptor — not present in the shipped artifact.
- Whether adherence data is uploaded via BLE→app→cloud or BLE→cloud directly — **Unknown**; only the ordering (AddressVerify / PlaceOrder) cloud calls are Observed.
