# Original decompilation notes

The files in [decompilation/](decompilation/doc-README.md) are the original supplied research notes, preserved verbatim. Their internal links remain together. They are historical evidence, not a current compatibility list or specification for OpenPressure.

| Original note | Topic |
| --- | --- |
| [Index](decompilation/doc-README.md) | Original research scope and evidence conventions |
| [Scanning](decompilation/doc-02-scan-identification.md) | Advertisements and protocol identification |
| [STP](decompilation/doc-00-common-stp.md) | Original secure-transport observations |
| [WLP](decompilation/doc-01-common-wlp.md) | Original command protocol observations |
| [Blood pressure](decompilation/doc-blood-pressure.md) | Original blood pressure paths |
| [Weight/body composition](decompilation/doc-weight-body-composition.md) | Research outside the implemented app scope |
| [Pulse oximetry](decompilation/doc-pulse-oximeter.md) | Research outside the implemented app scope |
| [Thermometers](decompilation/doc-thermometer.md) | Research outside the implemented app scope |
| [ECG](decompilation/doc-ecg.md) | Research outside the implemented app scope |
| [Smart cap](decompilation/doc-smartcap.md) | Research outside the implemented app scope |

Corrections and subsequent findings are integrated into the [current protocol reference](../protocol.md) and [safety boundaries](../safety.md). In particular:

- `FE4A`, `ecbe3980…` or `b305b680…` alone cannot establish STP: WLP uses overlapping service/control UUIDs.
- The native STP nonce, authenticated data, tag, key derivation and framing are recovered in the current reference. They are no longer an unexamined black box.
- The inspected GLS_EX analyzer exposes glucose fields. Its existence does not establish a BP payload mapping; Optional Service RACP is a separate supported history path.
- The X7 EOSL record map and bounded profile-registration workflow come from its original model assets and class paths. They are not interchangeable with older X7 maps.
- `getDeviceDetailsWithKey` reads requested Device Information fields in the inspected path; its name does not establish a shared secret or key-programming exchange.
- Android bonding, transport readiness, encrypted cuff authorization and X7 profile registration are distinct stages.

No APK, proprietary library or private health trace is included with these notes.
