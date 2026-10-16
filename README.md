# OpenPressure

An independent Android app for Bluetooth blood pressure monitors. Keep readings on your phone, view trends, add notes, and export your history without an account or cloud service.

**Tested monitor:** Omron X7 Smart AFib **HEM-7380T1-EOSL**. Pairing, clock setup and manual history import have been confirmed by the user; version 0.1.13 is reported working. Other Bluetooth blood pressure families are expected to work where they use an implemented protocol, but remain **untested**. Read-only vendor history paths are also implemented for ten additional models listed in the compatibility guide; they remain hardware-untested in OpenPressure. Unknown maps are rejected. See [compatibility](docs/compatibility.md) before trying another monitor.

The app is experimental. First use requires accepting the experimental-use notice, including the risk of incorrect operation or device damage. OpenPressure is an independent project and is not affiliated with Omron.

## Get started

Requires **Android 8 or newer** and Bluetooth.

1. Install the APK, open the app, and accept the first-use notice.
2. Enable Bluetooth and grant the requested Bluetooth permissions. Android 8–11 also need location permission and Location Services for scanning.
3. Open **Devices**, put the cuff in pairing mode using its manual, and scan. Choose **Pair** for initial setup. On the tested X7, choose user 1 or 2 **in the phone app** when asked, check the label on the cuff, and confirm registration. This saves a settings backup before updating its selected-user setting and clock.
4. Wake the cuff again if it powers off. Use **Connect** for routine connections and **Import monitor history** to import stored readings.
5. Open **History** to filter by **All users**, **User 1**, or **User 2**, view trends, edit notes, or export CSV or PDF.

Known vendor-memory imports read all mapped user banks without resetting unread counters. Older models use a separate application-key pairing flow; see [using a monitor](docs/usage.md#other-vendor-memory-monitors). X7 history imports include both user banks. Repeated imports deduplicate existing readings. Interrupted imports keep readings already saved; reconnect and retry. Use the monitor's instructions for taking measurements.

## Automatic imports and Health Connect

Automatic X7 imports listen for changed record metadata in Bluetooth advertisements, then fetch history. Choose **Automatic import settings** to use visible-app listening (default), turn it off, enable a background notification, or use Android companion mode. Companion mode needs Android 12+, Bluetooth enabled and separate Android approval for each saved monitor. Background modes request notification permission on Android 13+; alerts show a count of new readings without pressure values. Android power management and cuff advertising affect when imports happen. See [automatic imports](docs/automatic-import.md).

[Health Connect export](docs/health-connect.md) is optional and **off by default**. Choose your own bank, 1 or 2, and grant blood pressure write permission. Only that bank's eligible blood pressure readings are exported. Pulse, AFib flags, notes and raw data stay local.

The profile selection is shared between Overview and History and remembered when you reopen the app. The interface offers 52 language/region options; the name OpenPressure stays the same in every language. See [languages](docs/localization.md) for availability and translation review.

## Your data

The app has no Internet permission, account, analytics or backend. Readings and snapshots stay in private app storage; saved pairing keys use Android Keystore. Android app backup and device transfer are disabled. Export anything you want to keep before uninstalling.

Deleting a reading or using **Clear local history** affects this phone only. Clearing offers All users / User 1 / User 2 and confirms the number of readings across all dates. It keeps cuff memory and existing Health Connect copies. Automatic imports respect local deletions recorded by current versions; a deliberate manual history import can restore those readings. Saved monitors, keys and snapshots remain.

Exports may contain health data and device identifiers. Review and redact them before sharing. An irregular-pulse flag is separate from AFib; the decoded X7 AFib flag still needs comparison against monitor results. Measurement timestamps do not include their original timezone.

**Export PDF**, available on Overview and History, uses the selected profile and offers all time or an inclusive date range. Reports contain a portrait overview, landscape blood pressure and pulse charts, and landscape tables with every selected reading, monitor flags and notes. Long reports paginate automatically. See [PDF reports](docs/usage.md#pdf-reports).

## Build and contribute

Use JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Configure the SDK through Android Studio or `local.properties`, then run:

```sh
./gradlew :protocol:check :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Maintainers can build and publish signed APKs through the manual [GitHub release workflow](docs/releasing.md). See [development](docs/development.md) for setup and offline checks, [contributing](CONTRIBUTING.md) for changes, and the [documentation index](docs/README.md) for protocol, safety and validation evidence.

[MIT license](LICENSE) · Copyright (c) 2026 Joschua Becker EDV.
