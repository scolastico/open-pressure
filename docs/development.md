# Development

## Build requirements

Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0 and the included Gradle 8.14.3 wrapper. The app compiles against SDK 36, targets SDK 35 and supports Android 8/API 26+. Health Connect is available separately on Android 9+ with a provider.

Open the project in Android Studio or create an untracked `local.properties` pointing to your SDK:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

For a fresh SDK, the optional helper downloads Google's command-line tools, accepts SDK licenses and installs the packages used here:

```sh
bash tools/setup-sdk.sh /absolute/path/to/Android/Sdk
```

The current dependencies are declared in the Gradle files: Android Gradle plugin 8.13.2, Kotlin 2.3.0, Health Connect 1.1.0, WorkManager 2.10.5 and coroutines 1.10.2. The wrapper and initial dependency setup require network access. The installed app has network permission for optional, off-by-default [HTTP forwarding](http-forwarding.md) to the user's configured endpoint and for the daily [update check](updates.md) against GitHub's releases API.

## Checks

Fast checks run independently of Android/Gradle. The protocol runner uses Java assertions, and the Python runner checks SQLite migrations and storage behavior:

```sh
bash tools/test.sh
python3 tools/test-storage.py
python3 tools/check-locales.py
python3 tools/test-release.py
```

Run the Android unit tests, build and lint with the protocol checks:

```sh
./gradlew :protocol:check :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

When the SDK and dependency caches are already populated, the same suite can run offline:

```sh
./gradlew --offline :protocol:check :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. A debug build is suitable for local testing. The manual [GitHub release workflow](releasing.md) commits the chosen semantic version and commit-count revision, then builds and signs a release APK using repository secrets. Signing keys and local SDK configuration do not belong in the repository.

The protocol suite checks packet bounds/fields, SFLOAT and kPa, dates, manufacturer metadata including exact encrypted-type lengths, automatic-import eligibility, device-report redaction, WLP framing/fragmentation, model matching and write-policy rejection. It also checks standard CMAC/CCM vectors, synthetic fresh ECDH pairing and saved-key sessions, challenge authentication, integrity/replay rejection, record decoding and incomplete transfers. Vendor tests additionally exercise all ten full-bank maps, indexed notification channels, model-bound read gates, legacy key permits and strict END validation. App/storage tests cover backups, registration permits, automatic-import/presence/notification policy, deletion receipts, Health Connect export, recoverable key persistence, memory-record fingerprints and migrations. PDF model tests cover inclusive date/profile filtering, receipt-time fallback, unit conversion, statistics, immutable snapshots and chart pagination. These checks do not establish physical monitor compatibility, Android UI behavior, translation quality or provider interoperability.

`MonitorServiceImportTest` uses Robolectric with Android resources and a simulated Bluetooth peer to exercise the actual service callbacks, ATT queue, protocol parsers, SQLite persistence and localized status presentation. It covers legacy application-key acknowledgments through both history banks, automatic saved-key imports with their checkpoint and clock skip, device-report collection and its once-per-monitor prompt, notification/write callback ordering, optional clock outcomes, standalone timeout recovery, disconnects, invalid END responses and stale callbacks. Bluetooth calls are mocked; service orchestration and storage are real. Keep regression coverage at this boundary when changing BLE lifecycle behavior. Pure protocol/queue tests alone cannot catch a missing service transition or a warning hidden by the UI.

These tests add test-only Robolectric and Mockito dependencies. The first run also downloads Robolectric's Android runtime; populate that runtime cache with an online test run before running offline. They simulate Android behavior and do not replace the phone/cuff and visual checks in the hardware guide.

Forwarding tests use real SQLite and a loopback HTTP server with synthetic data. They exercise POST bodies/Authorization/idempotency, 2xx/error/redirect handling, restarts and retries, endpoint binding, schema v4-to-v5 preservation, transaction rollback, consent defaults, passthrough isolation, scheduling failure and cancellation. UI tests isolate WorkManager scheduling so they cannot dispatch real requests. Service tests cover passthrough callbacks and accurate stored/queued summaries, including a storage-policy change during an import. The receiver contract and pending-data policy are in [HTTP forwarding](http-forwarding.md).

Reminder tests exercise local-time/weekday scheduling, one-time completion, daylight saving, exact-access fallbacks, stale deliveries and notification privacy using Robolectric. Ingestion tests cover committed local/manual/live/passthrough timing and rollback without changing BLE commands. Activity tests cover editing, persistence, permission actions and configuration changes. These checks simulate Android; validate physical-phone delivery separately using the [reminder checklist](hardware-validation.md#measurement-reminders).

Virtual-user tests cover schema v5-to-v6 preservation, durable assignment/FIFO/restart behavior, receipt deduplication, captured passthrough/destination policy, rollback, arbitrary profile counts and scoped deletion. Activity/coordinator tests exercise explicit assignment, later/ignored/blocked notifications and foreground recovery. Service callbacks verify that pending assignments are accepted without prematurely claiming history or uploads. Health Connect tests require matching owner and renewed consent after mode changes; PDF snapshots group and name virtual profiles independently of monitor banks. See [virtual users](virtual-users.md) for the ownership contract.

### PDF rendering checks

`PdfReportInstrumentation` is a native Android fixture runner with synthetic data only. It generates English, German and Arabic PDFs containing 121 records, mixed units, missing values and a note spanning several pages, then checks every page's orientation with Android's PDF renderer. On a dedicated test emulator, run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w org.openpressure.app.test/org.openpressure.app.pdf.PdfReportInstrumentation
mkdir -p /tmp/open-pressure-pdf-output
for lang in en de ar; do
  adb -s emulator-5580 exec-out run-as org.openpressure.app cat "cache/pdf-fixtures/report-$lang.pdf" > "/tmp/open-pressure-pdf-output/report-$lang.pdf"
done
python3 tools/test-pdf-output.py /tmp/open-pressure-pdf-output
```

Replace the emulator serial with your dedicated test device. The host check requires Poppler (`pdfinfo` and `pdftotext`) and verifies record/note completeness, original units, timestamp seconds and all page orientations. Inspect rendered pages as well: extracted text does not establish visual layout or correct bidirectional presentation. Separately exercise the range dialog, empty selection, Android save/cancel and rotation during the file picker. Keep generated reports out of the repository.

## Source layout

| Directory | Responsibility |
| --- | --- |
| `protocol/` | Android-independent parsing, commands, secure sessions, operation queue and history state |
| `app/src/main/java/org/openpressure/app/ble/` | Capability discovery, Android bonding/GATT and serialized Bluetooth work |
| `app/src/main/java/org/openpressure/app/data/` | Private SQLite storage, diagnostics, snapshots, exports and key wrapping |
| `app/src/main/java/org/openpressure/app/automatic/` | Automatic import settings, services and companion presence |
| `app/src/main/java/org/openpressure/app/health/` | Optional Health Connect preparation, outbox and workers |
| `app/src/main/java/org/openpressure/app/forwarding/` | Optional HTTP destination/settings, measurement payloads, upload outbox and retry worker |
| `app/src/main/java/org/openpressure/app/reminders/` | Phone reminder configuration, local-time scheduling, minimal measurement timing and notifications |
| `app/src/main/java/org/openpressure/app/profiles/` | Named-user settings, foreground ownership picker, lifecycle coordination and count-only assignment alerts |
| `app/src/main/java/org/openpressure/app/updates/` | Daily GitHub release check, version comparison and remind/skip state |
| `app/src/main/java/org/openpressure/app/pdf/` | Immutable report selection, statistics and native PDF rendering |
| `app/src/main/` | Native Android UI, manifest and localized resources |
| `tools/` | Protocol/storage checks and optional SDK setup |
| `docs/` | Current guides and verbatim original research notes |

## Protocol development

Start with the [protocol reference](protocol.md), [safety policy](safety.md) and [compatibility status](compatibility.md). The original notes are retained for traceability; corrected current findings take precedence. A similar marketed name or shared UUID is insufficient evidence for another model's map or handshake.

Keep outgoing command gates at the real GATT boundary, validate plaintext before encryption, and require matching acknowledged state before enabling subsequent operations. Preserve strict model identity, bounded fragment/range lengths, explicit cancellation and connection-generation guards. New model maps need their own source evidence and negative-path tests. Do not weaken rejection to make an unknown cuff proceed.

Use synthetic/redacted fixtures with clear provenance. Do not commit APKs/XAPKs, proprietary `.so` libraries, extracted executable code, real pairing material, raw health/device traces or private machine paths. The hashes and class/method observations in the reference are sufficient to identify inspected artifacts without distributing them.

## Release review

The current app version is declared in `app/build.gradle`; the release workflow updates its semantic version and commit-count revision together. Retain upgrade compatibility and first-use experimental consent, and run the checks above after changes. State the exact tested model and operation; do not describe a software-only release as newly hardware-verified.

Check locale resources for missing format arguments and plural behavior, UI layout with long/RTL text, saved user selection across restart and bank separation. Use the [hardware checklist](hardware-validation.md) for any phone/cuff claims. Publish only intended source/docs and release artifacts; retain the [MIT license](../LICENSE) attribution to Joschua Becker EDV. See [contributing](../CONTRIBUTING.md) for review/report guidance.
