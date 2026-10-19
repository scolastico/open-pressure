# Documentation

Current guides describe the implementation and its evidence. Hardware results apply to the exact tested model and operation; automated tests alone do not establish interoperability.

| Guide | Contents |
| --- | --- |
| [Compatibility](compatibility.md) | Tested monitor, implemented but untested protocols, unsupported devices |
| [Using a monitor](usage.md) | Pairing, user banks, history, exports and troubleshooting |
| [Automatic imports](automatic-import.md) | Eligible monitors, visible/background modes, companion approval, notifications and local deletions |
| [Device reports](device-reports.md) | Shareable technical report for untested monitors, its contents and how to submit it |
| [Update checks](updates.md) | Daily GitHub release check, update dialog choices and how to turn it off |
| [Health Connect](health-connect.md) | Optional one-bank export, privacy, timestamps and retry behavior |
| [HTTP forwarding](http-forwarding.md) | Optional measurement POSTs, Authorization, passthrough and durable retry queue |
| [Measurement reminders](reminders.md) | Phone schedules, weekday repetition, recent-measurement skipping and Android permissions |
| [Virtual users](virtual-users.md) | Optional named profiles, persistent ownership queue, assignment notifications and export isolation |
| [Protocol reference](protocol.md) | Service selection, X7 map and framing, independently recovered cryptography, source evidence |
| [Safety boundaries](safety.md) | Outgoing command policy, exact registration writes, backup requirements and memory limits |
| [omblepy comparison](omblepy-comparison.md) | Pinned source audit, per-model maps, implemented vendor paths and validation limits |
| [Omron reference sandbox](omron-sandbox.md) | Replaying synthetic fixtures through Omron Connect's own code, setup, fixture format and per-model findings |
| [Bluetooth BP coverage research](bluetooth-coverage.md) | Exact catalog coverage inventory, REA hypotheses, new reference fixtures and remaining Bluetooth BP families |
| [Hardware validation](hardware-validation.md) | Existing evidence and focused phone/cuff test procedure |
| [Languages](localization.md) | Available languages, Android language selection and translation checks |
| [GitHub releases](releasing.md) | Manual version, commit-count revision, APK signing and publication |
| [Development](development.md) | Build requirements, checks, source layout and release guidance |

## Original research

The [decompilation notes](reference/README.md) preserve the original supplied `doc-*.md` files verbatim for traceability. They describe static analysis of the original app, including other device categories that OpenPressure does not implement. Some early conclusions are superseded by the current protocol reference: a shared vendor service does not identify STP, the native CCM format has been recovered, X7 has its own WLP map, and glucose-extension payloads are not established as blood pressure records. Use the current guides for implementation decisions.
