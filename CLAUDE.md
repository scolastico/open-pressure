# Instructions for AI agents

These instructions apply to all AI agents and subagents working in this repository.
Follow the documented project rules and standards. For a clear task, proceed with
the requested work; ask only when genuine ambiguity or a necessary deviation needs
the user's decision. Also read `~/.claude/CLAUDE.md`.

## Start with the maintained documentation

- [CONTRIBUTING.md](CONTRIBUTING.md): contribution, privacy, evidence and repository rules.
- [Development](docs/development.md): setup, source layout and validation commands.
- [Documentation index](docs/README.md): authoritative guides for each feature.
- Before device/protocol work, read [protocol](docs/protocol.md),
  [safety](docs/safety.md), [compatibility](docs/compatibility.md) and
  [hardware validation](docs/hardware-validation.md).
- Read the relevant feature guides: [usage](docs/usage.md),
  [automatic imports](docs/automatic-import.md),
  [Health Connect](docs/health-connect.md) and [localization](docs/localization.md).
- Before network or passthrough changes, read [HTTP forwarding](docs/http-forwarding.md).
- Before reminder or alarm changes, read [measurement reminders](docs/reminders.md).
- Before profile, assignment or ownership changes, read [virtual users](docs/virtual-users.md).

Use current guides for implementation decisions. Original supplied notes under
`docs/reference/decompilation/` remain verbatim; put corrections in maintained
guides and update their index/links. Update documentation when behavior changes.

## Critical device safety warning

**Be extremely careful with every byte sent to a physical device. A few incorrect
bytes can corrupt calibration or firmware and permanently brick the device.**
This warning deliberately repeats the [safety policy](docs/safety.md). Never use
real hardware to try guessed commands, addresses, payloads or write sequences.
Verify the exact model, target, command, length and every payload byte against
documented evidence and the outgoing command policy before enabling transmission.
If the effect is uncertain, stop that device operation and investigate with
synthetic fixtures; do not send bytes merely to see what happens. Passing software
tests does not prove that a command is safe for physical hardware.

## Implementation boundaries

- Keep changes focused and follow the existing code layout, formatting and resource
  structure. Keep Android-independent protocol logic in `protocol/`; use the app's
  existing BLE, storage, automatic-import, health and PDF components for their roles.
- Preserve actual-model and service/channel verification. A UI hint, similar name
  or shared UUID never authorizes another model's memory map or handshake.
- Preserve outgoing command checks at the real GATT boundary and plaintext checks
  before encryption, strict bounds/ACK validation, cancellation and connection
  generation guards. Do not enable guessed registers, unrestricted memory access,
  calibration/firmware/deletion commands or generic addressed writes. Keep the
  documented explicit-Pair registration exception narrowly scoped to its exact
  model (or an untested X7 identity listed in the write-aspect index under the
  hidden developer override), immutable plan, durable backup and live permit; Connect and automatic
  import must not acquire registration/key-programming privileges.
- Preserve first-use consent, privacy defaults, user-bank isolation and local data
  across upgrades. Keep migrations, deduplication, deletion receipts, pending keys
  and partial validated imports recoverable. Local deletion must not delete cuff
  memory or existing Health Connect copies.
- Health Connect stays optional, off by default and limited to the selected bank
  and documented BP fields, with write-only permission and stable retry identity.
  Keep network forwarding off by default and restricted to the user's explicitly
  configured HTTP(S) destination. Preserve the temporary upload queue, destination
  binding, acknowledgment-based cleanup and hash-only delivery receipts. Never
  log/export Authorization values or include them in measurement payloads. In
  passthrough mode, do not retain new readings in history, Health Connect or raw
  packet diagnostics; existing history and explicit safety backups remain intact.
- Device reports stay free of readings, timestamps, names, addresses, serial
  numbers/system IDs, memory contents and keys; collecting them must never send
  extra commands to a monitor. Update checks contact only GitHub's releases API
  for this repository, stay user-disableable and never download or install.
- Measurement reminders are phone-only notifications. Preserve weekday/local-time
  scheduling, permission fallbacks, stale-alarm rejection and occurrence-only
  suppression. Old history receipt time must not count as a recent measurement;
  passthrough suppression may retain only the documented minimal timing metadata.
- Virtual profiles are local identities, separate from physical monitor banks.
  Never send virtual profile IDs to a device. Preserve the durable oldest-first
  assignment inbox, captured storage/destination policy and hash-only ownership
  receipts. Dismissed notifications and mode changes must never discard pending
  readings. Require explicit Health Connect profile selection and renewed consent
  after a mode change; recheck ownership immediately before each provider write.
- Put user-facing text in localized resources, preserve placeholders/plurals and
  invariant product names, and check long text, larger fonts and RTL presentation.
  Explain user choices in plain language; protocol internals belong in technical
  documentation. Do not add clinical diagnoses or pressure classifications.
- Use synthetic or fully redacted fixtures. Do not commit real health/device
  traces, identifiers, pairing material, keys, private machine paths, APK/XAPK
  research artifacts, proprietary libraries/executable code or generated reports.
  Preserve the MIT license and existing copyright attribution.

## Validation and reporting

Add meaningful tests for new parsing, state, storage and safety behavior. Changes
to BLE/service/callback lifecycles also need integration tests exercising the real
service orchestration and callback ordering, including cancellation, stale
callbacks, disconnects and failures; helper/state-machine tests alone are
insufficient. Match additional checks to the affected feature as described in the
development and hardware guides.

Fast checks:

```sh
bash tools/test.sh
python3 tools/test-storage.py
python3 tools/check-locales.py
python3 tools/test-release.py
```

Android checks (JDK 17 and the SDK specified in the development guide):

```sh
./gradlew :protocol:check :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Report checks actually run, failures or unavailable checks, and remaining hardware
validation. Distinguish source-derived behavior, synthetic tests and exact
phone/cuff observations: software checks do not establish physical compatibility,
firmware safety, visual/translation quality or provider interoperability. Do not
broaden hardware-tested claims beyond the documented model and operation.
