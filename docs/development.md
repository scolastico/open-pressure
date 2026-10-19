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

The protocol suite checks packet bounds/fields, SFLOAT and kPa, dates, manufacturer metadata including exact encrypted-type lengths, automatic-import eligibility, device-report redaction, WLP framing/fragmentation, model matching and write-policy rejection. It also checks standard CMAC/CCM vectors, synthetic fresh ECDH pairing and saved-key sessions, challenge authentication, integrity/replay rejection, record decoding and incomplete transfers. Vendor tests additionally exercise all ten maps, record and index checksums, index parsing with its trust rule and user selection, per-model read block limits, appended ranges and early ending at an unanswered bank, indexed notification channels, model-bound read gates, legacy key permits including padded acknowledgments, and strict END validation. App/storage tests cover backups, saved per-monitor import users (schema v6-to-v7 keeps both users), registration permits, automatic-import/presence/notification policy, deletion receipts, Health Connect export, recoverable key persistence, memory-record fingerprints and migrations. PDF model tests cover inclusive date/profile filtering, receipt-time fallback, unit conversion, statistics, immutable snapshots and chart pagination. These checks do not establish physical monitor compatibility, Android UI behavior, translation quality or provider interoperability.

`MonitorServiceImportTest` uses Robolectric with Android resources and a simulated Bluetooth peer to exercise the actual service callbacks, ATT queue, protocol parsers, SQLite persistence and localized status presentation. It covers legacy application-key acknowledgments (including padded ones), index-driven imports with the untrusted-index fallback, the import-users choice, Read all with the skipped HEM-7156T second bank, automatic saved-key imports with their checkpoint, imports that no longer write Current Time or Omron Date Time, device-report collection and its once-per-monitor prompt, notification/write callback ordering, refusal of a standalone Current Time write on vendor monitors, an unacknowledged or busy END after complete history, disconnects, invalid END responses and stale callbacks. Bluetooth calls are mocked; service orchestration and storage are real. `MonitorServiceClockSyncTest` does the same for the Sync clock session: legacy, 14-byte and X7 writes, the threshold skip, rejected and missing acknowledgments, GATT errors without retry, disconnects while writing, an acknowledgment that arrives before the last chunk's write callback, a backup that completes after a disconnect (also after a reconnect with a new preview) without writing, the preview timeout, unreadable settings (including an erased X7 block), a phone date outside 2024–2050, backup failure, the chosen-time and override gates, whether the button is offered, and refusal during Pair, history, automatic imports, on the encrypted X7 and below ATT MTU 27. Keep regression coverage at this boundary when changing BLE lifecycle behavior. Pure protocol/queue tests alone cannot catch a missing service transition or a warning hidden by the UI.

These tests add test-only Robolectric and Mockito dependencies. The first run also downloads Robolectric's Android runtime; populate that runtime cache with an online test run before running offline. They simulate Android behavior and do not replace the phone/cuff and visual checks in the hardware guide.

Forwarding tests use real SQLite and a loopback HTTP server with synthetic data. They exercise POST bodies/Authorization/idempotency, 2xx/error/redirect handling, restarts and retries, endpoint binding, schema v4-to-v5 preservation, transaction rollback, consent defaults, passthrough isolation, scheduling failure and cancellation. UI tests isolate WorkManager scheduling so they cannot dispatch real requests. Service tests cover passthrough callbacks and accurate stored/queued summaries, including a storage-policy change during an import. The receiver contract and pending-data policy are in [HTTP forwarding](http-forwarding.md).

Reminder tests exercise local-time/weekday scheduling, one-time completion, daylight saving, exact-access fallbacks, stale deliveries and notification privacy using Robolectric. Ingestion tests cover committed local/manual/live/passthrough timing and rollback without changing BLE commands. Activity tests cover editing, persistence, permission actions and configuration changes. These checks simulate Android; validate physical-phone delivery separately using the [reminder checklist](hardware-validation.md#measurement-reminders).

Virtual-user tests cover schema v5-to-v6 preservation, durable assignment/FIFO/restart behavior, receipt deduplication, captured passthrough/destination policy, rollback, arbitrary profile counts and scoped deletion. Activity/coordinator tests exercise explicit assignment, later/ignored/blocked notifications and foreground recovery. Service callbacks verify that pending assignments are accepted without prematurely claiming history or uploads. Health Connect tests require matching owner and renewed consent after mode changes; PDF snapshots group and name virtual profiles independently of monitor banks. See [virtual users](virtual-users.md) for the ownership contract.

### Omron reference sandbox

Shared device fixtures under `protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures/` are replayed against OpenPressure by `tools/test.sh` (protocol level) and `FixtureReplayTest` (real `MonitorService`), so they run with the normal checks. With a locally supplied Omron Connect package, `tools/omron-sandbox.sh` also replays them through Omron's own Bluetooth code, in parallel test JVMs sized to the machine (override with `-Pomron.forks=N` or `OMRON_FORKS=N`), and prints both results side by side. Without the package, `:omron-sandbox:testDebugUnitTest` skips the Omron side. Setup, fixture syntax and limits are in the [Omron reference sandbox](omron-sandbox.md) guide; never commit the Omron package or anything extracted from it.

The sandbox also executes the local ARM64 `libnativeOmsec.so` using test-only Unidbg/Unicorn2 dependencies. Native tests verify pairing and authenticated encryption against a synthetic peer and skip without the ARM64 split. Encrypted X7 fixtures (`secure` directive) run Omron's native code, `MonitorService` and the protocol sessions against one cuff-side test peer, `SecurePeer`, which `X7TestPeer` also uses. Fixture entropy is repeatable and each run closes its native VM; see the guide for saved-state conventions and encrypted-fixture limits.

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
| `app/src/main/java/org/openpressure/app/developer/` | Hidden developer options: stored per-aspect write switches and the write-support viewer |
| `app/src/main/java/org/openpressure/app/updates/` | Daily GitHub release check, version comparison and remind/skip state |
| `app/src/main/java/org/openpressure/app/pdf/` | Immutable report selection, statistics and native PDF rendering |
| `app/src/main/` | Native Android UI, manifest and localized resources |
| `protocol/src/testFixtures/` | Shared synthetic device fixtures, their parser and the protocol-level replay |
| `omron-sandbox/` | Omron reference sandbox; the Omron package in `omron-sandbox/apk/` stays local |
| `tools/` | Protocol/storage checks, the Omron sandbox command and optional SDK setup |
| `docs/` | Current guides and verbatim original research notes |

## Protocol development

Start with the [protocol reference](protocol.md), [safety policy](safety.md) and [compatibility status](compatibility.md). The original notes are retained for traceability; corrected current findings take precedence. A similar marketed name or shared UUID is insufficient evidence for another model's map or handshake.

Keep outgoing command gates at the real GATT boundary, validate plaintext before encryption, and require matching acknowledged state before enabling subsequent operations. Preserve strict model identity, bounded fragment/range lengths, explicit cancellation and connection-generation guards. New model maps need their own source evidence and negative-path tests. Do not weaken rejection to make an unknown cuff proceed.

Use synthetic/redacted fixtures with clear provenance. Do not commit APKs/XAPKs, proprietary `.so` libraries, extracted executable code, real pairing material, raw health/device traces or private machine paths. The hashes and class/method observations in the reference are sufficient to identify inspected artifacts without distributing them.

## Developer options

> [!WARNING]
> **These switches allow writes to a monitor's memory that are not confirmed for its model, and they unlock unstable connection paths that have never run on a real monitor. Such a write can permanently corrupt the monitor's settings or make it unusable. They are meant only for people validating a model with a monitor they are prepared to lose.** Read the [safety policy](safety.md#write-aspect-index) first. Nothing here is a user feature; it is intentionally absent from the user guide.

The clock switch is read only by the explicit **Sync clock** action ([clock-block write](safety.md#clock-block-write)), the registration switch only by the explicit plain-WLP **Pair** registration ([X7 profile registration](safety.md#x7-profile-registration)); no other memory write uses these switches. Only the clock block and the registration of HEM-7380T1-EOSL are confirmed (both work there without any developer option); every other listed entry is untested, so those writes work on such monitors only while the matching switch is on. The two [experimental switches](#experimental-paths) open code paths that exist but have never run against a physical monitor; there is no other way to test them on hardware. Apart from the [confirmed EOSL run](hardware-validation.md#confirmed-run-x7-smart-afib-hem-7380t1-eosl), which used the clock switch and a developer-chosen time, everything described here is source-derived and covered by synthetic tests only.

**Unlock.** Open **Devices → Advanced** and tap the version label at the bottom (`Version <name> (<code>)`) five times, with no more than three seconds between taps; a longer pause restarts the count. After the third and fourth tap a short hint shows how many taps remain. The fifth tap unlocks the options and adds a **Developer options** button above the version label. Tapping the label sends nothing to a monitor. The state is stored locally in the app's private `developer` shared preferences (`DeveloperOptions`), not in the measurement database, and is not part of any export.

**Switches.** There is one switch per write aspect that can be overridden at all, followed by the experimental paths. Every switch asks for confirmation when it is turned on (**I understand, enable**); cancel, back or tapping outside leaves it off. Turning a switch off needs no confirmation. No switch sends anything by itself.

| Switch | Key | Effect |
| --- | --- | --- |
| **Allow clock sync on monitors where it is untested** | `allow_untested_clock_block` (`CLOCK_BLOCK`) | While it is on, **Sync clock** appears on monitors whose clock block is untested (without it, those monitors show no clock control), and its confirmation there shows an untested-monitor warning. Confirmed monitors need no switch and show no warning. Blocked monitors stay unavailable. |
| **Allow profile registration on monitors where it is untested** | `allow_untested_registration` (`REGISTRATION`) | While it is on, an explicit plain-WLP **Pair** on an untested registration identity (HEM-7380T1-EBK, or the experimental HEM-7196T1-FLE and HEM-7196T1-FLEO) runs the same [registration](safety.md#x7-profile-registration) as on the EOSL: settings backup, user choice on the phone, the two staged writes and END. The user-choice card and the confirmation name the identity being registered and show an untested-monitor warning. Without the switch, Pair on such a monitor ends in the read-only ready state with "Profile registration is not enabled for <identity>" and writes nothing. It never registers on Connect, imports, Fetch now or automatic imports, on an encrypted X7 session, on a bare `HEM-7380T1` or `HEM-7196T1` or on any blocked identity. HEM-7380T1-EOSL needs no switch. |
| **Use the encrypted connection when pairing an X7** | `experimental_force_x7_encrypted` | Experimental, hardware-untested. A user-started **Pair** on an X7 whose advertisement says plain (types 1, 2, 3 or 6) selects the encrypted route (`X7_SECURE_WLP`) instead: fresh application pairing with the existing handshake, still requiring acknowledged SYS writes and an ATT MTU of at least 92, then the cuff authorization poll. The packet log records "Encrypted route forced by developer option" (no key material). It sends no profile registration in that session, because registration exists only on the plain route, and it does not renew the Android bond. A successful run saves an application key, so later **Connect** uses the encrypted route for that monitor until it is forgotten. Connect, automatic imports and Fetch now never read the switch. The encrypted route has never completed on a physical cuff and may simply fail. |
| **Allow clock sync over an encrypted X7 connection** | `experimental_clock_sync_encrypted_x7` | Experimental, hardware-untested. On a manual **Connect** whose encrypted X7 session is established and authorized (ATT MTU at least 27, all other Sync clock conditions unchanged), **Sync clock** is offered and sends the same single clock-block frame, checked in plaintext by `validateX7ClockBlock` before encryption and then encrypted like every other secure command; the acknowledgment arrives through the authenticated incoming path. The write-aspect gate is unchanged: the EOSL entry is confirmed for the frame, not for the transport, and EBK still also needs the clock switch. Without this switch, encrypted X7 sessions show no clock control. |

`UNSENT_COUNTERS` has no switch: it is blocked on every model because it is not implemented, and no developer option can unblock a blocked aspect. Blocked entries without code (unsent counters, the HEM-7322T and HEM-7377T1 clock block) and the STP handshake get no switch. Every unlock starts with all switches off, and hiding the options turns every switch off. A switch that is on stays on across app restarts while the options remain unlocked, and it only counts while they are unlocked. Connect, Import, Read all, Fetch now and automatic imports never read the untested-write switches; Pair reads only the registration switch and the forced-encryption switch.

### Experimental paths

The experimental switches (`DeveloperOptions.Experiment`: `FORCE_X7_ENCRYPTED`, `CLOCK_SYNC_ENCRYPTED_X7`) are stored as `experimental_<name>` and read through `DeveloperOptions.allowsExperimental`, which counts only while the options are unlocked; locking and unlocking clear them like the aspect switches. They change only which already-implemented path a user-started session takes; they authorize no new command. The encrypted session keeps every rule of the [encrypted X7 path](protocol.md#x7-encrypted-wlp-and-authorization): plaintext checks before encryption, integrity, counter and replay checks on incoming frames, and no generic encrypted-write escape. The [device report](device-reports.md) lists the names of the experimental switches that were in effect for its session under `developer` (for example `["force_x7_encrypted"]`), names only. Each session reads its switch when it starts (Pair when the X7 profile is selected; Sync clock when the button is evaluated, when the session begins and again at confirmation), and a failure to read it counts as off. Validation steps are in [hardware validation](hardware-validation.md#experimental-encrypted-x7-paths).

**Developer-chosen clock time.** While the developer options are unlocked, the Sync clock confirmation adds **Time to set (developer option)**, on confirmed monitors and on untested monitors allowed by the clock switch alike; no extra switch is needed. It only chooses the target time of a Sync clock that the index already permits; it never permits a write by itself. The field is prefilled with the phone time in the usual date-and-time format. Left unchanged, it writes the phone time read at confirmation; an edited value becomes the target instead. The same rules apply as for the phone time: the clock is written as target + 1 s, must lie in 2024–2050, and nothing is written when the monitor's clock is within 60 seconds of the target. Its purpose is validation: a deliberately wrong time (for example another hour or day) proves on the monitor's own display that the write took effect, even when its clock was already correct. The service checks again on confirmation that the options are still unlocked; if they were hidden by then, a chosen time is refused and the confirmation stays open for the phone time or Cancel. Afterwards, run Sync clock again with the phone time to restore the correct clock. An invalid value shows an error and writes nothing.

**Write support viewer.** **Write support by monitor** lists each saved monitor with its name, reported model and, for every aspect, the `WriteAspectIndex` status (**Confirmed**, **Untested** or **Blocked**) and its evidence note. The viewer resolves the saved model string with `WriteAspectIndex.resolve`, which maps only the documented exact regional variants to a table id and the firmware name `X7 Smart AFib` to `HEM-7380T1-EOSL`; any other string is looked up as reported, so an unlisted or merely similar name shows **Blocked**. It shows the status of the model string saved for the monitor; for a monitor that was never verified in a session, that string may be only a hint and is not a verified identity. The list is rebuilt whenever the screen returns to the foreground, so a monitor forgotten meanwhile disappears. Evidence notes are technical text, shown left-to-right and untranslated. The viewer reads only local data.

**Hide.** **Hide developer options** asks for confirmation, then locks the options, turns every switch off and removes the button. Unlocking again starts from all switches off.

If saving a developer option fails (for example on a full or read-only data partition), unlocking, a switch change and hiding show **Could not save developer options** instead of crashing. The screen then shows the state the app is currently using: Android keeps the new value in memory even when the disk write fails, so this may differ from what is saved. After an app restart the previous saved state may come back, so repeat **Hide developer options** or the switch change and check that it took effect.

**Source guard.** `WriteAspectSourceGuardTest` (run by `tools/test.sh` and `:protocol:check`) scans `app/src/main` and `protocol/src/main` and fails when `requireWriteAspect(`, `allowsUntested(`, `allowsChosenClockTime(` or `allowsExperimental(` appears outside its allow-list: `DeviceWritePolicy.java` (the definition), `app/src/main/java/org/openpressure/app/developer/` and `app/src/main/java/org/openpressure/app/ble/MonitorService.java`. `MonitorService` may contain exactly one `allowsUntested(` call, the private helper `developerOverride(Aspect)` through which Sync clock and Pair registration read their override at request time, exactly one `allowsChosenClockTime(` call, the private helper through which Sync clock reads the chosen-time permission, exactly one `allowsExperimental(` call, the private helper through which the experimental paths read their switch, and no `requireWriteAspect(` call; the guard fails on any other count. Any new caller needs review and an explicit allow-list entry.

Tests: `DeveloperOptionsTest` (storage, tap counting, clearing on lock and unlock, the registration and experimental keys, the chosen-time permission following the unlocked state), `DeveloperOptionsActivityTest` (every switch and its confirmation, viewer rows for all three aspects, refresh on resume, failed saves, hide), `MainRegistrationTest` (the identity named in the registration confirmation, the untested warning and the report offer), `MonitorServiceRegistrationGateTest` (EOSL and EBK registration through the real service callbacks, the read-only fallback without the switch, a switch withdrawn before confirmation, disconnect, rejection and timeout), `MonitorServiceSecureX7Test` (the forced encrypted Pair handshake against a simulated encrypted peer, the plain path without the switch, Connect and automatic imports ignoring it, and Sync clock over the established encrypted session), `MonitorServiceHem7196Test` (the experimental HEM-7196T1: encrypted and plain imports of its 60-slot banks, catalog names and contradicting advertisements refused before any write, the wait for Android's bond on the encrypted route (including the held model read, and no retry of an encrypted frame), and the registration and clock gates), `MainDeveloperUnlockTest` (version-label unlock and a failed unlock save through the real Devices screen) and `MainClockSyncTest` (the Sync clock button and when it is hidden, confirmation, the warning only with the override, the chosen-time field whenever the options are unlocked, the report offer and the clock-sync backup export through the real Devices screen) run with `:app:testDebugUnitTest`. `MonitorServiceClockSyncTest` drives the Sync clock session through the real service callbacks, including the confirmed EOSL with locked options, a chosen time on it with unlocked options, the EBK that still needs the switch and the untested legacy path, and the `sync-clock` fixtures replay it at protocol and service level (`flow sync-clock`, `expect clock acknowledged|skipped|rejected`; see the [Omron sandbox](omron-sandbox.md#fixture-format)).

## Release review

The current app version is declared in `app/build.gradle`; the release workflow updates its semantic version and commit-count revision together. Retain upgrade compatibility and first-use experimental consent, and run the checks above after changes. State the exact tested model and operation; do not describe a software-only release as newly hardware-verified.

Check locale resources for missing format arguments and plural behavior, UI layout with long/RTL text, saved user selection across restart and bank separation. Use the [hardware checklist](hardware-validation.md) for any phone/cuff claims. Publish only intended source/docs and release artifacts; retain the [MIT license](../LICENSE) attribution to Joschua Becker EDV. See [contributing](../CONTRIBUTING.md) for review/report guidance.
