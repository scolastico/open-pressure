# Contributing

Start with [development](docs/development.md) for setup and checks. The app is experimental and handles health data and device configuration. Keep changes focused, preserve local data across upgrades and describe behavior in plain language.

For a bug report, include the app version, Android version, exact monitor label/firmware, operation and expected/actual result. State whether the problem involves Pair, Connect, manual history, automatic import or Health Connect. Do not post raw diagnostics, EEPROM JSON, health exports, Bluetooth addresses, account details or pairing material in a public issue. A minimal redacted error and reproduction are usually enough to begin. For an untested monitor, use the **Device report** issue form with the app's [device report](docs/device-reports.md); it is designed for public sharing.

For another monitor, inspect [compatibility](docs/compatibility.md), the [protocol reference](docs/protocol.md) and [safety boundaries](docs/safety.md). Similar names and shared vendor services do not establish a compatible memory map. Do not relax model checks, invent register meanings or enable undocumented writes to work around rejection. Record source evidence and exact hardware results separately; synthetic tests do not make a model hardware-tested.

For implementation changes, add meaningful tests for new parsing, state or safety behavior, then run the relevant protocol/storage/unit/build/lint checks. Keep first-use experimental consent, privacy defaults, bank separation and failure/cancellation behavior intact. Use synthetic or fully redacted fixtures. UI text should explain the user's choice without exposing protocol internals; detailed technical rationale belongs in the maintained docs.

For translations, preserve format arguments and plural forms, check long text and right-to-left layouts, and use consistent names for user banks, pairing and local-only deletion. Report whether a native speaker reviewed the translation. Follow the existing resource structure rather than hard-coding translated UI strings.

Pull requests should explain the concrete problem, resulting behavior and checks performed. Include known limits or required hardware checks. Use conventional commit messages when committing. Keep private plans/specs, local SDK paths, signing keys, build products, APK/XAPK research artifacts (including the Omron package in `omron-sandbox/apk/` and everything extracted from it), proprietary native libraries and real health traces out of the repository. Original supplied decompilation notes are preserved verbatim under `docs/reference/decompilation/`; integrate corrections into the current guides and update their index/links.

Contributions use the project's [MIT license](LICENSE). Retain the existing copyright attribution to Joschua Becker EDV.
