# Omron reference sandbox

OpenPressure's tests show that the app agrees with our own idea of how a monitor answers. The Omron reference sandbox adds a second referee: Omron Connect's own Bluetooth code. It runs the official app's communication library on your computer against scripted, synthetic connections ("fixtures") and checks whether Omron's code sends the same packets and decodes the same readings as OpenPressure. This lets us check protocol assumptions for monitors we do not own.

The sandbox never talks to a real monitor, a phone or the network. A fixture that Omron's code accepts is evidence about Omron's app, not proof that a physical monitor answers that way or that a command is safe on real hardware. The [safety policy](safety.md) and [hardware validation](hardware-validation.md) still apply to every change in what OpenPressure sends.

## How it works

- **Fixtures** are text files in `protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures/`. Each describes one connection: the advertisement, Device Information values, pinned time and key, the phone's expected writes and the monitor's scripted replies, and the readings both clients must decode. They contain only synthetic data and are committed.
- **OpenPressure** is replayed twice against every fixture, without the Omron package: at protocol level by `bash tools/test.sh`, and through the real `MonitorService` by `./gradlew :app:testDebugUnitTest`. Both are part of the normal checks, so a fixture keeps protecting OpenPressure after it has been confirmed.
- **Omron Connect** is replayed by the `omron-sandbox/` module. It converts the locally supplied app package to Java classes, runs Omron's communication library in Robolectric (Android framework on the JVM, no emulator) and replaces only the Bluetooth peer with the fixture. Omron chooses the model configuration exactly like the app: from the IDs in the advertised `BLEsmart_<category><model>` name, looked up in the app's model catalog.

Each client is compared with the fixture, not with the other client, so the fixture is the common reference. Steps only one client performs are marked in the fixture with a reason and listed as open findings in every report.

## Setting up the Omron package

The Omron app is not part of this repository and must never be committed or redistributed, together with anything derived from it (converted classes, native libraries, configuration files, transcripts).

1. Obtain Omron Connect (`com.omronhealthcare.omronconnect`) yourself, either as an `.xapk` from a download source you trust, or from your own phone: `adb shell pm path com.omronhealthcare.omronconnect` lists the base and split APKs, which you copy with `adb pull`. The sandbox needs the base APK; the `arm64-v8a` split is kept for the later native-library phase.
2. Put the `.xapk`, or the `.apk` files, into `omron-sandbox/apk/`. The folder is gitignored.
3. Run `./gradlew :omron-sandbox:omronExtract` (the sandbox tests also run it). It writes `omron-sandbox/build/omron/`: Omron's classes and the libraries they reference as `omron-classes.jar`, the model catalog, the memory-map configurations, the native library and the app version. The conversion uses dex2jar from Maven Central and takes about a minute.

Without a package, the Omron side is skipped and every other check still runs. The sandbox was last checked with Omron Connect 2026.09.03 (2026091010); this is information, not a requirement.

## Running it

```sh
tools/omron-sandbox.sh                                   # every fixture
tools/omron-sandbox.sh hem-7156t/connect-read-history     # one fixture
tools/omron-sandbox.sh resolve                           # how Omron's entry points were found
tools/omron-sandbox.sh diff <old-version> <new-version>  # transcript changes between Omron versions
```

For each fixture the command prints three results, followed by the fixture's open findings:

| Line | Meaning |
| --- | --- |
| `omron …` | Omron's code against the fixture, with the path to its transcript |
| `ours (app)` | OpenPressure's `MonitorService` against the fixture |
| `ours (protocol)` | OpenPressure's protocol sessions against the fixture |

Each result is one of:

- `matches`: the client followed every step and decoded exactly the expected readings.
- `diverged at step N`: the client wrote something else; the report shows the expected and actual frame with decoded fields.
- `rejected`: the client refused or stopped (an error callback, an exception, a disconnect, or no further write). Omron's state and error code are included; its raw log is in the `.log` file next to the transcript.
- `unsupported`: Omron's catalog has no model for the advertised IDs.

Transcripts are kept under `omron-sandbox/build/omron-sandbox/transcripts/<Omron version>/`. Keep the `build` folder when you update the Omron package so `diff` can show what changed in the protocol.

### Tuning a fixture

When Omron diverges or rejects, adjust the fixture's Omron-side steps or replies (not OpenPressure) and run the fixture again. When Omron matches but OpenPressure does not, the difference is a finding about OpenPressure. Changing what OpenPressure sends to a monitor still goes through the normal evidence and [safety](safety.md) review; the sandbox result alone is not enough.

## Fixture format

One directive per line; `#` starts a comment; hex may contain spaces.

| Directive | Meaning |
| --- | --- |
| `name <group>/<flow>` | Must match the file path; list the name in the `index` file |
| `flow connect` / `flow pair` | Reconnect with the saved key, or register the key first |
| `advertise name=… services=<uuid>,… [msd=<hex>]` | Advertisement; drives Omron's model choice |
| `dis model=… firmware=… software=… systemid=<hex>` | Device Information; `model` drives OpenPressure's model choice |
| `time <local date-time> zone=<id>` | Pinned clock for clock writes |
| `key <16 bytes hex>` | Pinned application key (pairing stages it instead of generating one) |
| `random <hex>` | Pinned entropy, reserved for the native-library phase |
| `alias <name> <uuid>[,<uuid>…]` | Characteristic name; several UUIDs form 16-byte legacy channels |
| `memory fill=<byte>` / `memory <address> <hex>` | Synthetic memory used by `reads` |
| `write <alias> <hex>` | A frame the phone must write; channel parts are reassembled |
| `notify <alias> <hex>` | The monitor's reply; split over channels for multi-UUID aliases |
| `reads <address> <length> chunk=<n>` | Expands to WLP read requests and replies from `memory` |
| `expect reading user=… sys=… dia=… pulse=… time=…` | A reading every client must decode |
| `expect ours rejected` / `expect omron rejected` | That client is expected to refuse the device |

Prefix a step with `[omron-only: reason]` or `[ours-only: reason]` when only one client performs it. The reason is mandatory and should name its evidence.

## HEM-7156T findings

With Omron Connect 2026.09.03, Omron's code follows the three HEM-7156T fixtures (`hem-7156t/connect-read-history`, `hem-7156t/pair` and `hem-7156t-aap/connect-read-history`) and decodes the same reading as OpenPressure. Agreed in both clients: the saved-key unlock, key registration, the start frame, the first record read and the record decoding.

Open findings, all from synthetic sandbox runs:

- Omron's catalog calls its `HEM-7156T_AP` entry "HEM-7156T" (model ID `0x057A`) with one user. Its configuration declares one 60-record area at `0x98`. OpenPressure's pinned omblepy map also reads a second bank at `0x458`, which Omron's code never reads.
- Omron confirms encryption with `02` on every connection and does not send `01` after registering the key.
- Omron reads the index block at `0x10` and setting-information block 3 at `0x3C`, then only the records its index marks as unsent. It rejects a record whose sequence number at offset 10 does not match the index. OpenPressure reads complete banks and does not use the index.
- Omron ends the session with `08 0f ff ff ff 00 00 f8`; OpenPressure sends `08 0f 00 00 00 00 00 07`. The monitor's reply to Omron's END is not confirmed.
- Omron wrote no clock in these flows; OpenPressure writes Current Time before END.

`MemoryMapComparisonTest` writes the memory-map comparison for all HEM-7156T configurations to `omron-sandbox/build/omron-sandbox/memory-map.txt`.

## Limits and technical notes

- **Native library**: encrypted models (X7 and others) use Omron's `libnativeOmsec.so`. The sandbox does not emulate it yet; any call fails the run with `native omsec not emulated yet`.
- **Obfuscated code**: Omron's release build renames most classes and methods. The sandbox hooks only Android Bluetooth classes and fixed native names, and finds Omron's entry points by kept names and exact signatures (`OmronAnchors`). `tools/omron-sandbox.sh resolve` names any entry point a new Omron version breaks.
- **Timing**: virtual time passes only while Omron waits, so its pacing delays and timeouts behave as on a phone without real waiting. Callbacks from different Omron threads may appear in either order in a transcript.
- **Isolation**: each fixture runs with fresh copies of Omron's classes, because Omron keeps process-wide state. The sandbox blocks HTTP for the whole test JVM because Omron's analytics library tries to send tracking data.
- **Verification**: dex2jar output has no stack-map frames, so the sandbox's test JVM runs with bytecode verification disabled.
