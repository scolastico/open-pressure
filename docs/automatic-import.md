# Automatic imports

OpenPressure listens for changed X7 record sequence/count metadata in Bluetooth advertisements, then opens a bounded history transfer. The advertisements contain metadata, not pressure/pulse values. It does not continuously poll GATT for measurements or implement live WLP streaming. The original-app class/method evidence is in the [protocol reference](protocol.md#automatic-transfer-source-evidence).

## Choose a mode

Open **Automatic import settings** from Overview or Devices.

| Mode | Behavior |
| --- | --- |
| Off | Manual imports only; stops listening and cancels an automatic transfer |
| When the app is open, default | Filtered listening while a monitor/import screen is visible |
| In the background with a notification | Foreground service listens after leaving the app; persistent notification offers Stop automatic imports |
| Android companion | Android-approved presence observation for each saved cuff; no persistent foreground-service notification |

Notification mode is available on Android 8–9 and 12+. Android 10–11 background scans need separate background location permission, which OpenPressure does not request. Companion mode requires Android 12+ and companion-device support. Other monitor families retain manual operation pending model-specific implementation/validation.

Only saved X7 identities with known plain manufacturer data and an existing Android bond can start an automatic connection. The service rechecks the actual DIS model and WLP layout. Pairing/streaming advertisements are ignored. Encrypted pairing, key exchange and profile registration cannot start automatically.

## Android companion approval

Enable Bluetooth, save/pair the cuff, then choose **Approve with Android** for that monitor. Approval is a separate Android association; it does not register or re-pair the cuff. Each saved monitor needs approval. Both existing-bond and live BLE filters target the exact saved Bluetooth address, and the returned address/association is verified. Forgetting a monitor removes its association/observer.

If Bluetooth is off, setup opens Android's standard enable prompt. Only a successful result with an enabled adapter resumes the selected setup; cancellation leaves it retryable. The target survives activity recreation and is checked against the current saved device and mode before continuing. There is no silent adapter enable call.

The reported endless-search problem on Pixel 9 Pro, Android 17, was resolved by enabling Bluetooth; Android approval was confirmed on that phone. If approval still searches, cancel the old dialog, check the existing bond, enable Bluetooth and ensure Location Services permit Android discovery before retrying. That report does not independently prove the effect of any particular discovery filter.

Android 12–15 use address-based presence observation; Android 16+ uses association-ID requests and presence events. BLE proximity and Bluetooth connection are tracked separately, so a completed GATT transfer does not erase continuing BLE presence. Timing still depends on the cuff advertising, Android's presence service, phone power management and permissions. Force-stop, disabled Bluetooth or unavailable companion support can prevent imports. Reopen the app to inspect status, or explicitly choose notification mode if needed.

Android behavior references: [background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [companion association](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), and the inspected [AOSP `checkBoundDevicesIfNeeded()` implementation](https://android.googlesource.com/platform/frameworks/base.git/+/8ca4abde0e746248d1188d203237807b0bd8ef8b/packages/CompanionDeviceManager/src/com/android/companiondevicemanager/CompanionDeviceDiscoveryService.java). The latter checks existing bonded/connected devices for an address-based `BluetoothDeviceFilter`; a BLE-only filter misses that path.

## Transfers and retries

Changed per-bank sequence/count metadata triggers one transfer: read the fresh 68-byte settings/index image, populated history slots in both banks, then END. No registration, clock write, key programming, unread-counter reset, deletion or firmware command is sent. Unknown and unused memory regions are not probed. Clock-unset/forced-transfer flags alone do not cause settings writes or repeated imports.

Only a full expected record count and validated END checkpoint the advertised revision. Completed revisions persist across restart; duplicate advertisements and transient flags do not retrigger them. Failed imports have a one-minute device cooldown; scan failures re-arm listening after one minute. Partial validated readings remain saved and deduplicate on retry. Manual Scan or Connect takes priority over automatic work. Expected cuff shutdown after a validated END preserves the completed result.

## New-reading alerts

With [virtual users](virtual-users.md) enabled, new automatic readings first enter the persistent assignment inbox. The separate **Measurement assignment** channel asks you to choose who measured and opens the oldest-first picker. Ignored or blocked notifications retain every pending measurement for the next foreground session. Assignment must commit before a reading becomes eligible for local history or exports; raw monitor bank numbers never guess ownership.

When an automatic transfer saves new readings and finishes while monitor/import screens are not visible, the **New readings** channel posts one count-only alert opening unfiltered History. Pressure, pulse, bank and device address are absent. Repeated packets, zero inserts and foreground completion do not alert. A failed/interrupted transfer can still alert for new readings already saved; inspect History/diagnostics for completeness. END and disconnect cannot produce two alerts for the same attempt.

On Android 13+, a background mode requests `POST_NOTIFICATIONS` when needed. **Allow reading notifications** requests permission where Android allows, otherwise opens app notification settings. A blocked New readings channel opens channel settings. Denial suppresses reading alerts without interrupting read-only imports. Companion mode still has no persistent foreground-service notification; notification mode retains its service notification according to Android's platform behavior.

## Local deletions

SQLite deletion receipts retain SHA-256 fingerprints for locally removed readings. Automatic history refreshes skip them, including after **Clear local history** for one bank or all users. A deliberate manual import may restore them. Deletions from versions predating receipt support cannot be reconstructed. Local imports may include both banks; [Health Connect](health-connect.md) independently exports only its configured bank.

## Validation scope

The user reports version 0.1.13 working and Android companion approval is confirmed. Detailed captured evidence establishes manual history/registration, rather than every automatic/background lifecycle path. Software checks cover advertisement parsing, revision policy, presence tracking, notifications and deletion receipts. Follow the [hardware checklist](hardware-validation.md#automatic-imports-and-phone-controls) to check measurement arrival, restart, power-management and permission behavior on a real phone/cuff.
