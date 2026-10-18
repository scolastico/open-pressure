# Omron reference sandbox

OpenPressure's tests show that the app agrees with our own idea of how a monitor answers. The Omron reference sandbox adds a second referee: Omron Connect's own Bluetooth code. It runs the official app's communication library on your computer against scripted, synthetic connections ("fixtures") and checks whether Omron's code sends the same packets and decodes the same readings as OpenPressure. This lets us check protocol assumptions for monitors we do not own.

The sandbox never talks to a real monitor, a phone or the network. A fixture that Omron's code accepts is evidence about Omron's app, not proof that a physical monitor answers that way or that a command is safe on real hardware. The [safety policy](safety.md) and [hardware validation](hardware-validation.md) still apply to every change in what OpenPressure sends.

## How it works

- **Fixtures** are text files in `protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures/`. Each describes one connection: the advertisement, Device Information values, pinned time and key, the phone's expected writes and the monitor's scripted replies, and the readings both clients must decode. They contain only synthetic data and are committed.
- **OpenPressure** is replayed twice against every fixture, without the Omron package: at protocol level by `bash tools/test.sh`, and through the real `MonitorService` by `./gradlew :app:testDebugUnitTest`. Both are part of the normal checks, so a fixture keeps protecting OpenPressure after it has been confirmed.
- **Omron Connect** is replayed by the `omron-sandbox/` module. It converts the locally supplied app package to Java classes and runs Omron's communication library in Robolectric (Android framework on the JVM, no Android device required). The Bluetooth peer is replaced with the fixture. Native Omsec calls run the locally extracted ARM64 library in Unidbg, with a repeatable entropy source. Omron chooses the model configuration exactly like the app: from the IDs in the advertised `BLEsmart_<category><model>` name, looked up in the app's model catalog.

The sandbox drives Omron's library in the order the app's connectivity layer uses for a blood pressure monitor: connect in registration or authentication mode, read the product index, read the device settings when pairing a multi-user model, request vital data (flags `0x1010` for a transfer, `0x1210` for pairing, the user in the resume object) and hand the settings received with the completed transfer back to `setDeviceSettings` for the users read. Omron's library then writes the clock and the cleared unsent counters into the monitor's memory and ends the session itself; the app never cancels a healthy session. The sandbox stands in for the app's profile with Omron's default date of birth (1900-01-01) and pins the wall clock Omron's time setting uses to the fixture's `time`, so the settings frames are repeatable. The app serves one selected user per session; a multi-user fixture reads each expected user in turn and makes one final settings call with the last transfer's settings bundle. Its settings frames therefore describe that synthetic sequence, including the last user's cleared counter and changed birthday.

Each client is compared with the fixture, not with the other client, so the fixture is the common reference. Steps only one client performs are marked in the fixture with a reason and listed as open findings in every report.

## Setting up the Omron package

The Omron app is not part of this repository and must never be committed or redistributed, together with anything derived from it (converted classes, native libraries, configuration files, transcripts).

1. Obtain Omron Connect (`com.omronhealthcare.omronconnect`) yourself, either as an `.xapk` from a download source you trust, or from your own phone: `adb shell pm path com.omronhealthcare.omronconnect` lists the base and split APKs, which you copy with `adb pull`. The sandbox needs the base APK; native Omsec execution also requires the `arm64-v8a` split containing `libnativeOmsec.so`.
2. Put the `.xapk`, or the `.apk` files, into `omron-sandbox/apk/`. The folder is gitignored.
3. Run `./gradlew :omron-sandbox:omronExtract` (the sandbox tests also run it). It writes `omron-sandbox/build/omron/`: Omron's classes and the libraries they reference as `omron-classes.jar`, the model catalog, the memory-map configurations, the native library and the app version. The conversion uses dex2jar from Maven Central and takes about a minute.

Without a package, the Omron side is skipped and every other check still runs. The sandbox was last checked with Omron Connect 2026.09.03 (2026091010); this is information, not a requirement.

## Running it

```sh
tools/omron-sandbox.sh                                   # every fixture
tools/omron-sandbox.sh hem-7156t/connect-read-history     # one fixture
tools/omron-sandbox.sh --explore hem-7156t/pair           # keep answering Omron after a divergence
tools/omron-sandbox.sh resolve                           # how Omron's entry points were found
tools/omron-sandbox.sh diff <old-version> <new-version>  # transcript changes between Omron versions
```

Both replay tests also accept a comma-separated `-Dfixture` list for a focused batch, for example:

```sh
./gradlew -Dfixture=hem-7155t/pair,hem-6232t/pair :omron-sandbox:testDebugUnitTest --tests '*OmronFixtureShard*'
./gradlew -Dfixture=hem-7155t/pair,hem-6232t/pair :app:testDebugUnitTest --tests '*FixtureReplayTest'
```

Run Gradle commands sequentially: they share build and test-output directories. The shell helper accepts one fixture name or `all`.

Each fixture needs a fresh Robolectric and Omron start of one to two minutes, so the Omron replay runs in parallel test JVMs. Gradle distributes test classes rather than parameters, so the fixtures are dealt round-robin to eight shard classes (`OmronFixtureShard0Test` … `OmronFixtureShard7Test`, by position in the fixture index modulo 8); a shard with no selected fixture runs nothing. The number of parallel JVMs is detected from the machine: half the logical processors, at most (physical RAM − 4 GB) / 3 GB, at most 8, and at least 1. The build prints the chosen value (`Omron sandbox: up to N parallel test forks`); override it with `-Pomron.forks=N` for Gradle or `OMRON_FORKS=N tools/omron-sandbox.sh` for the helper, for example with 2 on a machine that is also busy with other work.

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

When Omron diverges or rejects, adjust the fixture's Omron-side steps or replies (not OpenPressure) and run the fixture again. With `--explore`, the peer keeps answering after the first divergence with synthetic replies (reads from the fixture memory, echoed writes and END, acknowledged key frames), so one transcript shows everything Omron would send in the session; the reported result is still the first divergence, and the synthetic replies prove nothing about a monitor. When Omron matches but OpenPressure does not, the difference is a finding about OpenPressure. Changing what OpenPressure sends to a monitor still goes through the normal evidence and [safety](safety.md) review; the sandbox result alone is not enough.

## Fixture format

One directive per line; `#` starts a comment; hex may contain spaces.

| Directive | Meaning |
| --- | --- |
| `name <group>/<flow>` | Must match the file path; list the name in the `index` file |
| `flow connect` / `flow pair` / `flow read-all` | Reconnect with the saved key, register the key first, or reconnect and read every slot of the selected users' banks followed by any unconfirmed bank (HEM-7156T bank 2), without the index; Omron has no read-all operation, so its runner reports `unsupported` |
| `advertise name=… services=<uuid>,… [msd=<hex>]` | Advertisement; drives Omron's model choice |
| `dis model=… firmware=… software=… systemid=<hex>` | Device Information; `model` drives OpenPressure's model choice |
| `time <local date-time> zone=<id>` | Pinned clock for clock writes |
| `key <16 bytes hex>` | Pinned application key (pairing stages it instead of generating one) |
| `register user=<1\|2>` | User a `pair` flow registers on Omron's side (default 1); OpenPressure's legacy key is not per user |
| `import users=<1\|2\|both>` | Users OpenPressure imports (default both); Connect and Pair read only their populated slots and a `read-all` flow only their banks. A selection without a bank the model offers falls back to user 1; shared-bank models still read every bank. The protocol-level replay does not apply the HEM-7377T1 shared-bank user filter, which no fixture covers |
| `random <hex>` | Native Omsec entropy stream, consumed in order by its random-number callback; exhaustion fails the run |
| `alias <name> <uuid>[,<uuid>…]` | Characteristic name; several UUIDs form 16-byte legacy channels |
| `memory fill=<byte>` / `memory <address> <hex>` | Synthetic memory used by `reads` |
| `write <alias> <hex>` | A frame the phone must write; channel parts are reassembled |
| `notify <alias> <hex>` | The monitor's reply; split over channels for multi-UUID aliases |
| `reads <address> <length> chunk=<n>` | Expands to WLP read requests and replies from `memory` |
| `expect reading user=… sys=… dia=… pulse=… time=…` | A reading every client must decode |
| `expect ours rejected` / `expect omron rejected` | That client is expected to refuse the device |

Prefix a step or `expect reading` with `[omron-only: reason]` or `[ours-only: reason]` when it applies to only one client. The reason is mandatory and should name its evidence. Unannotated readings must be decoded by every client; annotated readings are required only for the named client and remain visible as open findings. Rejection expectations cannot be annotated.

## HEM-7156T findings

With Omron Connect 2026.09.03, Omron's code follows the three HEM-7156T fixtures (`hem-7156t/connect-read-history`, `hem-7156t/pair` and `hem-7156t-aap/connect-read-history`). On base-model Connect, both clients agree on the saved-key unlock, start frame, record read and decoded reading. OpenPressure deliberately rejects the AAP identity; Omron accepts it and decodes the same synthetic record. On Pair, both clients agree on key registration and START, but Omron's pairing flags `0x1210` return settings without reading history in this fixture, while OpenPressure imports the record. The Pair fixture marks its index and record reads and the expected reading as OpenPressure-only.

Open findings, all from synthetic sandbox runs:

- Omron's catalog calls its `HEM-7156T_AP` entry "HEM-7156T" (model ID `0x057A`) with one user. Its configuration declares one 60-record area at `0x98`. OpenPressure's pinned omblepy map also has a second bank at `0x458`, which Omron's code never reads. OpenPressure now reads it only during Read all, after the selected bank; Connect and Pair read user 1's populated slots only.
- Omron confirms encryption with `02` on every connection and does not send `01` after registering the key.
- On Connect, Omron reads the index block at `0x10` (44 bytes) and setting-information block 3 at `0x3C`, then only the records its index marks as unsent. It rejects a record whose sequence number at offset 10 does not match the index. OpenPressure reads the 68-byte settings/index image at `0x10` in 56-byte blocks and then the slots the index lists as populated (see [OpenPressure's reads](#openpressures-reads)). With one stored, unsent record the record read is the same frame in both clients.
- After the transfer Omron writes the monitor's memory (see [post-transfer settings writes](#post-transfer-settings-writes)); OpenPressure writes Current Time to a dedicated characteristic instead and never writes these monitors' memory.
- Both clients end the session with `08 0f 00 00 00 00 00 07`. Earlier sandbox versions cancelled the session instead of letting Omron finish it and therefore recorded Omron's error END `08 0f ff ff ff 00 00 f8`; the app does not send it in a normal session.

## Other legacy vendor-memory models

Each of these Connect fixtures reconnects with a saved key, reads one synthetic record per user and ends the session. Omron's code follows every one of them and decodes the same readings as both OpenPressure replays. On the index-supported models both clients send the same record-read frame, because the one stored record is also the one unsent record; HEM-7600T, whose records carry no sequence number, is read in full by OpenPressure:

| Fixture | Omron catalog entry (model ID) | Users | Record |
| --- | --- | --- | --- |
| `hem-7150t/connect-read-history` | HEM-7150T-Z (`0x0154`) | 1 | 16 bytes |
| `hem-7155t/connect-read-history` | HEM-7155T-EBK (`0x0264`) | 2 | 16 bytes |
| `hem-7342t/connect-read-history` | HEM-7342T-Z (`0x0152`) | 2 | 16 bytes |
| `hem-7361t/connect-read-history` | HEM-7361T-EBK (`0x0258`) | 2 | 16 bytes |
| `hem-7530t/connect-read-history` | HEM-7530T-Z (`0x0151`) | 1 | 14 bytes, byte pairs swapped |
| `hem-7600t/connect-read-history` | HEM-7600T (`0x001F`, config model_id HEM-7270C) | 1 | 14 bytes, byte pairs swapped |
| `hem-6232t/connect-read-history` | HEM-6232T-Z (`0x0144`) | 2 | 14 bytes, byte pairs swapped |

Each model in the table also has a `pair` fixture; `hem-7156t-aap/pair` covers the regional identity that OpenPressure rejects. With Omron Connect 2026.09.03, Pair registers one user, reads the index and settings, and returns no historical readings with flags `0x1210`. It then writes the registered user's cleared unsent counter, birthday block and clock, sends normal END and disconnects. The two-user models HEM-7155T, HEM-7342T, HEM-7361T and HEM-6232T register user 2. Their extra `readSettings(null)` call reuses the index/settings image and produces no additional link traffic in these fixtures. OpenPressure's accepted legacy Pair flows instead import history on the same connection, with the same index and populated-slot reads as Connect (a full read on HEM-7600T), and write Current Time; they never write these monitors' memory. These are synthetic observations of Omron's code, not physical monitor behavior.

They confirm OpenPressure's record addresses, record sizes and the decoding of pressure, pulse and date and time for these maps. They also show the checks Omron's code applies; OpenPressure now applies most of them:

- **Record checksum**: byte 14 of a 16-byte record (byte 12 of a 14-byte record) is the 8-bit sum of the bytes before it, computed on the logical (un-swapped) bytes. Both clients reject a record whose checksum does not match. OpenPressure counts it as a rejected slot and keeps the other readings; HEM-7322T and HEM-7377T1, which no fixture covers, stay exempt. See `hem-7155t/connect-bad-record-checksum`.
- **Index block**: both clients read the index at `0x10` (16-byte maps) or `0x260` (14-byte maps) before records. On the 14-byte maps the index uses the same swapped byte pairs as the records. Omron reads only the records it marks as unsent and checks each record's sequence number against it; OpenPressure reads every populated slot and does not check sequence numbers per record (open finding).
- **Full flag**: the legacy index prefix carries the same full flag as the X7 pointer: bit 14 of a user's position word marks every slot as populated, and the low byte is the next write position. In the sandbox Omron's code reads only `position` records when the flag is clear, even when the sequence counter exceeds the capacity and more records are marked unsent, and ignores bit 15 (`0x8005` behaves like `0x0005`). With the flag set it reads every slot, starting at the newest record and wrapping to slot 0. OpenPressure applies the same rule; `hem-7155t/connect-wrapped-index` (position word `0x4005`, 60 populated slots) shows both clients reading and decoding the whole bank, OpenPressure from slot 0 in 48-byte blocks and Omron from slot 4. Omron also keeps a full user's position word and unsent count in its post-transfer prefix write and clears only the last-read user's counter.
- **Setting-information blocks**: blocks not listed in `ignoreCheckSumBlocks` carry the same additive checksum (HEM-7530T block 3, HEM-7600T block 3); Omron refuses the index otherwise. OpenPressure checks HEM-7530T block 3 on the logical bytes and falls back to a full sweep when it fails; `hem-7530t/connect-bad-index-checksum` shows Omron rejecting the same corruption, and a local variant with a logically valid but wire-invalid block showed Omron accepting it, so both clients sum the same bytes. HEM-7600T has no index-driven read in OpenPressure, so its block 3 is not checked.
- **Product identity**: for HEM-7600T, both clients check byte 14 of the 16-byte identity in the start reply as the sum of bytes 0-13. Omron ends the session without reading when it does not match; OpenPressure refuses the session.
- **Session completion**: all these fixtures include Omron's post-transfer settings writes and full synthetic echo acknowledgments, followed by the normal END shared with OpenPressure. The former `ffffff` END finding came from the runner cancelling the session.

These are synthetic observations of Omron's code; they do not show how a physical monitor fills these fields.

`MemoryMapComparisonTest` compares the record areas of every Omron configuration that OpenPressure's model identification accepts and writes them to `omron-sandbox/build/omron-sandbox/memory-map.txt`. With Omron Connect 2026.09.03, the areas agree except for these:

- **HEM-7156T**: one area in Omron's configuration against our two banks (see above).
- **HEM-7155T K4 variants** (`HEM-7155T_K4-D`, `-EBK`, `-ESL`, WLD2.0): different layout. Their catalog names equal those of the regular HEM-7155T identities that OpenPressure accepts, so the Device Information model a K4 monitor reports decides whether it would be read with the wrong map. That is unconfirmed.
- **HEM-7377T1-ZAZ**: no plain record area (split WLD3.0 map), as already documented in the [omblepy comparison](omblepy-comparison.md).

## OpenPressure's reads

After the saved-key unlock and START, OpenPressure reads legacy vendor memory as follows; each difference from Omron is an annotated step and is printed as an open finding:

- **Index-supported models** (HEM-7150T, HEM-7155T, HEM-7156T, HEM-7342T, HEM-7361T, HEM-7530T, HEM-6232T): `reads <settings start> 68 chunk=56`, at `0x10` on 16-byte maps and at `0x260` on HEM-7530T and HEM-6232T. Omron reads 44 bytes and then setting-information block 3 (and block 4 on HEM-7530T) separately, so this step stays OpenPressure-only. OpenPressure then reads the populated slots of each selected user (every slot of a bank whose full flag is set) in record blocks (48 bytes on 16-byte maps, 56 on 14-byte maps), writes Current Time and sends END.
- **HEM-7600T** (no sequence numbers): a full read of its bank in 56-byte blocks, then Current Time and END.
- **Untrusted index** (a failed block-3 checksum, a next write position beyond the bank capacity, or an index read that fails): a full sweep of the selected users' banks in record blocks.
- **Read all**: a full sweep of the selected users' banks followed by any unconfirmed bank, without the index. Omron has no matching operation.

Open findings that closed: OpenPressure now uses the index, validates record checksums (Omron's models), the HEM-7530T block-3 checksum and the HEM-7600T identity checksum. Open findings that remain: Omron reads only unsent records and checks their sequence numbers, while OpenPressure reads every populated slot; Omron writes the monitor's memory after every transfer, while OpenPressure writes none and uses Current Time; Omron confirms encryption with `02`, and after registration OpenPressure sends `01` and Omron does not.

### Read all and negative fixtures

| Fixture | OpenPressure | Omron Connect 2026.09.03 |
| --- | --- | --- |
| `hem-7155t/read-all` | Reads every slot of user 2's bank only (`import users=2`) and decodes user 2's reading | `unsupported`: no read-all operation |
| `hem-6232t/read-all` | Reads every slot of both banks and decodes both readings | `unsupported`: no read-all operation |
| `hem-7155t/connect-bad-record-checksum` | Rejects user 2's record (one rejected slot, "History transfer incomplete"), keeps user 1's reading, writes Current Time and ends normally | Delivers user 1's reading, then reports `ParseMeasurement(postOGSCHandler2)Error` for user 2; the sandbox stops at that error callback, before Omron's settings writes and END (`rejected`) |
| `hem-7530t/connect-bad-index-checksum` | Does not trust the index, sweeps the whole bank and decodes the reading | Reports `CheckSumError OGSCMemoryMapAnalyzer.checkSettingReadIndex` after reading block 3, sends its error END `08 0f ff ff ff 00 00 f8` and disconnects without reading records (`rejected`) |

The bad-record fixture keeps an Omron-only user-2 reading expectation solely so that the sandbox requests user 2 from Omron; no client is expected to decode it. These describe Omron's code and OpenPressure against synthetic memory, not a physical monitor's corruption or recovery.

## Post-transfer settings writes

All nineteen indexed legacy Connect and Pair fixtures record two settings writes from Omron Connect 2026.09.03, followed by END `08 0f 00 00 00 00 00 07`. The frames come from the local synthetic exploration transcripts and are pinned as Omron-only steps; their acknowledgments are synthetic full echoes. Normal replay checks the exact requests and decoded results without exploration replies. OpenPressure's production command policy is unchanged and blocks addressed writes for these models.

The first write includes the index prefix with the last-read user's unsent counter cleared on Connect, or the registered user's counter cleared on Pair. A changed birthday block is merged with the prefix or the clock block when adjacent. The sandbox's default birthday is 1900-01-01; the clock is fixture time plus one second. Omron recomputes additive settings checksums. The 14-byte-record models swap byte pairs in the settings image as well as the records.

Data lengths below are decimal payload bytes, excluding the eight framing bytes. Pair prefixes are recorded independently: their bytes can differ from Connect when the synthetic index or selected user differs.

| Fixtures | First write: address, data length | Second write: address, data length |
| --- | --- | --- |
| HEM-7156T Connect/Pair, HEM-7156T-AAP Connect, HEM-7150T Connect | `0x54`, 30 bytes: prefix + birthday block 1 | `0x80`, 16 bytes: clock block 3 |
| HEM-7155T/HEM-7342T/HEM-7361T Connect | `0x54`, 16 bytes: prefix | `0x72`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7530T Connect | `0x2A4`, 30 bytes: prefix + birthday block 1 | `0x2D0`, 16 bytes: clock block 3 |
| HEM-6232T Connect | `0x2A4`, 16 bytes: prefix | `0x2C2`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7600T Connect | `0x286`, 14 bytes: prefix + birthday block 1 | `0x29A`, 10 bytes: clock block 3 |
| HEM-7150T Pair (user 1) | `0x54`, 30 bytes: prefix + birthday block 1 | `0x80`, 16 bytes: clock block 3 |
| HEM-7156T-AAP Pair (user 1) | `0x54`, 30 bytes: prefix + birthday block 1 | `0x80`, 16 bytes: clock block 3 |
| HEM-7155T Pair (user 2) | `0x54`, 16 bytes: prefix | `0x72`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7342T Pair (user 2) | `0x54`, 16 bytes: prefix | `0x72`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7361T Pair (user 2) | `0x54`, 16 bytes: prefix | `0x72`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7530T Pair (user 1) | `0x2A4`, 30 bytes: prefix + birthday block 1 | `0x2D0`, 16 bytes: clock block 3 |
| HEM-6232T Pair (user 2) | `0x2A4`, 16 bytes: prefix | `0x2C2`, 30 bytes: birthday block 2 + clock block 3 |
| HEM-7600T Pair (user 1) | `0x286`, 14 bytes: prefix + birthday block 1 | `0x29A`, 10 bytes: clock block 3 |

These results describe Omron's code against synthetic memory, including settings returned during Pair without a history read. They do not establish a physical monitor's reply, memory effects or compatibility, and do not authorize adding these writes to OpenPressure's legacy device path.

## Native Omsec execution

`ShadowOmsecNatives` forwards all eleven JNI entry points to the locally extracted `lib/arm64-v8a/libnativeOmsec.so`. Unidbg 0.9.9 with its Unicorn2 backend executes the ARM64 instructions on the host; Omron's pairing, ECDH, key derivation and AES-CCM code remain intact. These are test-only dependencies and are not included in the Android app.

The emulator replaces micro-ecc's registered entropy callback, which supplies both key generation and handshake randomness. A fixture's `random` bytes are consumed verbatim, without wrapping or extending them; provide enough for the complete exchange, including key-generation retries. When `random` is absent, a fresh `java.util.Random(0)` supplies a repeatable default stream. Explicit streams are preferable for pinned wire packets. Each fixture starts with a fresh native VM and entropy stream; cleanup closes remaining sessions even after a failed exchange. Unknown or already closed session handles are rejected before entering native code.

Native initialization takes either `null` for fresh pairing or Omron's **80-byte saved state**. This differs from the fixture's 16-byte application `key` used by legacy models. Native `GetLtk` returns that state after fresh pairing, an empty array after a successful reconnect, or `null` after rejection. Calls and return values are forwarded without translating these native conventions. Encrypted reconnect fixtures will also need Omron's Java-side saved-state/Keystore setup; the existing shared fixtures cover legacy HEM-7156T flows, not complete encrypted-model history transfers.

Run the focused native checks with:

```sh
./gradlew :omron-sandbox:testDebugUnitTest --tests '*Omsec*Test'
```

With the local ARM64 split, these checks exercise fresh pairing and saved-key authentication against an independent synthetic peer, packet encryption/decryption, invalid challenges, modified tags, replay rejection, deterministic entropy and cleanup. A Robolectric integration check invokes the private native declarations on Omron's real pairing object. Native execution tests skip when the package or ARM64 split is absent; a native call during a fixture instead reports the missing split explicitly. These are synthetic observations of the supplied binary and do not establish monitor compatibility.

## Limits and technical notes

- **Native library**: Omsec execution requires the local ARM64 split and a host supported by Unidbg/Unicorn2. The native bridge is tested with Omron Connect 2026.09.03; complete encrypted-model fixtures and their Android key storage remain separate work.
- **Obfuscated code**: Omron's release build renames most classes and methods. The sandbox hooks only Android Bluetooth classes and fixed native names, and finds Omron's entry points by kept names and exact signatures (`OmronAnchors`). `tools/omron-sandbox.sh resolve` names any entry point a new Omron version breaks.
- **Timing**: virtual time passes only while Omron waits, so its pacing delays and timeouts behave as on a phone without real waiting. Callbacks from different Omron threads may appear in either order in a transcript.
- **Isolation**: each fixture runs with fresh copies of Omron's classes, because Omron keeps process-wide state. The sandbox blocks HTTP for the whole test JVM because Omron's analytics library tries to send tracking data.
- **Memory**: every test JVM gets a 3 GB heap because one fixture holds Robolectric's Android framework and Omron's converted classes in a fresh class loader. With N parallel forks the replay can use about N × 3 GB plus JVM overhead; lower `-Pomron.forks` when memory is short. Static state such as the network block and the native Omsec runtime is per JVM, and every fixture writes only its own result and transcript files, so forks do not share mutable state.
- **Verification**: dex2jar output has no stack-map frames, so the sandbox's test JVM runs with bytecode verification disabled.
