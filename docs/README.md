# Documentation

Current guides describe the implementation and its evidence. Hardware results apply to the exact tested model and operation; automated tests alone do not establish interoperability.

| Guide | Contents |
| --- | --- |
| [Compatibility](compatibility.md) | Tested monitor, implemented but untested protocols, unsupported devices |
| [Using a monitor](usage.md) | Pairing, user banks, history, exports and troubleshooting |
| [Automatic imports](automatic-import.md) | Visible/background modes, companion approval, notifications and local deletions |
| [Health Connect](health-connect.md) | Optional one-bank export, privacy, timestamps and retry behavior |
| [Protocol reference](protocol.md) | Service selection, X7 map and framing, independently recovered cryptography, source evidence |
| [Safety boundaries](safety.md) | Outgoing command policy, exact registration writes, backup requirements and memory limits |
| [omblepy comparison](omblepy-comparison.md) | Pinned source audit, per-model maps, implemented vendor paths and validation limits |
| [Hardware validation](hardware-validation.md) | Existing evidence and focused phone/cuff test procedure |
| [Languages](localization.md) | Available languages, Android language selection and translation checks |
| [GitHub releases](releasing.md) | Manual version, commit-count revision, APK signing and publication |
| [Development](development.md) | Build requirements, checks, source layout and release guidance |

## Original research

The [decompilation notes](reference/README.md) preserve the original supplied `doc-*.md` files verbatim for traceability. They describe static analysis of the original app, including other device categories that OpenPressure does not implement. Some early conclusions are superseded by the current protocol reference: a shared vendor service does not identify STP, the native CCM format has been recovered, X7 has its own WLP map, and glucose-extension payloads are not established as blood pressure records. Use the current guides for implementation decisions.
