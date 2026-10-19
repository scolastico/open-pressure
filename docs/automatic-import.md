# Automatic imports

OpenPressure listens for changed record sequence/count metadata in Omron Bluetooth advertisements, then opens a bounded history transfer. The advertisements contain metadata, not pressure/pulse values. It does not continuously poll GATT for measurements or implement live WLP streaming. The original-app class/method evidence is in the [protocol reference](protocol.md#automatic-transfer-source-evidence).

## Choose a mode

Open **Automatic import settings** from Overview or Devices.

| Mode | Behavior |
| --- | --- |
| Off | Manual imports only; stops listening and cancels an automatic transfer |
| When the app is open, default | Filtered listening while a monitor/import screen is visible |
| In the background with a notification | Foreground service listens after leaving the app; persistent notification offers Stop automatic imports |
| Android companion | Android-approved presence observation for each saved cuff; no persistent foreground-service notification |

Notification mode is available on Android 8–9 and 12+. Android 10–11 background scans need separate background location permission, which OpenPressure does not request. Companion mode requires Android 12+ and companion-device support.

## Eligible monitors

Automatic imports cover saved monitors with a read-only history path: the X7 identities and the verified [vendor-memory models](compatibility.md). Eligibility follows the model saved when an X7 or vendor-memory connection verified it; the model a standard-profile monitor reports in Device Information only names that monitor and never makes it eligible. A saved monitor must have an existing Android bond and advertise a plain type 1, 2 or 6 record summary. Models that use an application key also need the key saved by an earlier **Pair**. The service rechecks the actual DIS model and channel layout on every connection. Pairing and streaming advertisements are ignored.

The additional experimental [Bluetooth catalog identities](bluetooth-coverage.md) follow the same eligibility rules. Their one-user models import only the available bank and offer no bank selector; a stale user-2 preference falls back to user 1. Their physical advertisement behavior has not been observed.

Standard Bluetooth Blood Pressure monitors and encrypted X7 sessions stay manual. An encrypted X7 needs its application handshake and on-cuff authorization, which are never started in the background. Pairing, key programming and profile registration cannot start automatically. The original app expects record summaries from its other monitors too, but only the tested X7's advertisement has been captured; see the [source evidence](protocol.md#automatic-transfer-source-evidence).

## Android companion approval

Enable Bluetooth, save/pair the cuff, then choose **Approve with Android** for that monitor. Approval is a separate Android association; it does not register or re-pair the cuff. Each saved monitor needs approval. Both existing-bond and live BLE filters target the exact saved Bluetooth address, and the returned address/association is verified. Forgetting a monitor removes its association/observer.

If Bluetooth is off, setup opens Android's standard enable prompt. Only a successful result with an enabled adapter resumes the selected setup; cancellation leaves it retryable. The target survives activity recreation and is checked against the current saved device and mode before continuing. There is no silent adapter enable call.

The reported endless-search problem on Pixel 9 Pro, Android 17, was resolved by enabling Bluetooth; Android approval was confirmed on that phone. If approval still searches, cancel the old dialog, check the existing bond, enable Bluetooth and ensure Location Services permit Android discovery before retrying. That report does not independently prove the effect of any particular discovery filter.

Android 12–15 use address-based presence observation; Android 16+ uses association-ID requests and presence events. BLE proximity and Bluetooth connection are tracked separately, so a completed GATT transfer does not erase continuing BLE presence. Timing still depends on the cuff advertising, Android's presence service, phone power management and permissions. Force-stop, disabled Bluetooth or unavailable companion support can prevent imports. Reopen the app to inspect status, or explicitly choose notification mode if needed.

Android behavior references: [background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [companion association](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), and the inspected [AOSP `checkBoundDevicesIfNeeded()` implementation](https://android.googlesource.com/platform/frameworks/base.git/+/8ca4abde0e746248d1188d203237807b0bd8ef8b/packages/CompanionDeviceManager/src/com/android/companiondevicemanager/CompanionDeviceDiscoveryService.java). The latter checks existing bonded/connected devices for an address-based `BluetoothDeviceFilter`; a BLE-only filter misses that path.

## Transfers and retries

Changed per-bank sequence/count metadata triggers one transfer. It imports the users chosen under **Readings to import** for that saved monitor (default both), captured when the transfer starts. An X7 reads its fresh 68-byte settings/index image and the populated history slots of those users. A vendor-memory model unlocks with its saved application key where it uses one, as **Connect** does, and imports exactly as a manual import does: index-supported models read the index and then the populated slots, falling back to every slot of the chosen users' banks when the index is untrusted; other models read every slot. Automatic imports never use **Read all**, so they never try the HEM-7156T's unconfirmed second bank. Both then send END. No registration, clock write, key programming, unread-counter reset, deletion or firmware command is sent. Unknown and unused memory regions are not probed. Clock-unset/forced-transfer flags alone do not cause settings writes or repeated imports.

Only a validated END after every expected record checkpoints the advertised revision. For an X7 that means the indexed record count of the chosen users; for a vendor-memory model, every selected slot (populated slots or, after a fallback, every slot) read without a rejected record, including a record that fails its checksum; a full read that reaches END with rejected slots is shown as complete but is not checkpointed. Completed revisions persist across restart; duplicate advertisements and transient flags do not retrigger them. Failed imports have a one-minute device cooldown; scan failures re-arm listening after one minute. Partial validated readings remain saved and deduplicate on retry. Manual Scan or Connect takes priority over automatic work. Expected cuff shutdown after a validated END preserves the completed result.

## Fetch now

Pulling down on Overview starts an explicit, user-requested transfer. It runs in every mode, including Off, and never changes the selected mode. It uses the same eligibility as automatic imports: a saved monitor with a verified eligible model, an existing Android bond, a plain type 1, 2 or 6 record summary with stored records and, where the model needs one, a key saved by an earlier **Pair**. It never acquires pairing, registration or key-programming privileges.

The fetch stops the automatic listener and scans the saved eligible addresses at low latency for 15 seconds. Each monitor is queued at most once per fetch with the advertisement it sent at that moment. Monitors are imported strictly one at a time through the transfer described above: the same unlock, **Readings to import** choice, index-driven reads, END, virtual-user inbox, deduplication, deletion receipts and alerts. Between imports the scan resumes while the window is open, so a monitor that starts advertising a few seconds later is still imported. The fetch ends when the window has passed, the queue is empty and no import is running; the automatic listener then resumes according to the mode.

The fetch ignores the completed-revision checkpoint and the one-minute failure cooldown, because the user asked for it. A validated END still checkpoints the advertised revision, and a failed fetch import still starts the cooldown for advertisement-triggered imports. Scan, Connect, Pair, Disconnect, turning Bluetooth off or closing the service cancel a running fetch; readings already validated stay saved. A fetch is refused while another monitor operation is in progress.

## New-reading alerts

With [virtual users](virtual-users.md) enabled, new automatic readings first enter the persistent assignment inbox. The separate **Measurement assignment** channel asks you to choose who measured and opens the oldest-first picker. Ignored or blocked notifications retain every pending measurement for the next foreground session. Assignment must commit before a reading becomes eligible for local history or exports; raw monitor bank numbers never guess ownership.

When an automatic transfer saves new readings and finishes while monitor/import screens are not visible, the **New readings** channel posts one count-only alert opening unfiltered History. Pressure, pulse, bank and device address are absent. Repeated packets, zero inserts and foreground completion do not alert. A failed/interrupted transfer can still alert for new readings already saved; inspect History/diagnostics for completeness. END and disconnect cannot produce two alerts for the same attempt.

On Android 13+, a background mode requests `POST_NOTIFICATIONS` when needed. **Allow reading notifications** requests permission where Android allows, otherwise opens app notification settings. A blocked New readings channel opens channel settings. Denial suppresses reading alerts without interrupting read-only imports. Companion mode still has no persistent foreground-service notification; notification mode retains its service notification according to Android's platform behavior.

## Local deletions

SQLite deletion receipts retain SHA-256 fingerprints for locally removed readings. Automatic history refreshes skip them, including after **Clear local history** for one bank or all users. A deliberate manual import may restore them. Deletions from versions predating receipt support cannot be reconstructed. Local imports may include both banks; [Health Connect](health-connect.md) independently exports only its configured bank.

## Validation scope

The user reports version 0.1.13 working and Android companion approval is confirmed. Detailed captured evidence establishes manual X7 history/registration, rather than every automatic/background lifecycle path. Automatic imports for vendor-memory models, including their index-driven reads, are implemented from source evidence and tested in software only; whether each model advertises the expected record summary needs a physical monitor. Fetch now is tested in software only, with the real service, its scan queue and connection callbacks against a simulated scanner and peer. Software checks cover advertisement parsing, revision policy, the vendor saved-key transfer and checkpoint, presence tracking, notifications and deletion receipts. Follow the [hardware checklist](hardware-validation.md#automatic-imports-and-phone-controls) to check measurement arrival, restart, power-management and permission behavior on a real phone/cuff.
