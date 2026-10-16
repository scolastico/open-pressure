# Compatibility

**Omron X7 Smart AFib HEM-7380T1-EOSL is the tested monitor.** The user reports version 0.1.13 working. Detailed supplied evidence confirms profile registration, correct clock and manual history import, including a complete 111/111-record transfer, on this model. Android companion approval was also confirmed on Pixel 9 Pro, Android 17, after enabling Bluetooth. This does not establish every background timing, notification, export or UI path on every phone.

Other Bluetooth blood pressure device families are expected to work where they expose an implemented protocol, but are **untested**. Marketed names such as Evolv, M4/X4, other X7/M7, RS7 or HeartGuide cover different firmware and service layouts. Selecting a model hint does not authorize a memory map or establish support for every variant.

| Protocol or feature | Implementation and evidence |
| --- | --- |
| X7 plain WLP, HEM-7380T1-EOSL | Tested registration, clock and manual history; settings/index read and validated END confirmed |
| X7 accepted DIS identities | Exact `HEM-7380T1`, `HEM-7380T1-EOSL` and `X7 Smart AFib`, case-insensitive; actual EOSL label remains required for registration. These aliases do not establish testing of another variant. |
| X7 encrypted WLP1COM_PAIR | Implemented application handshake, saved-key reconnect and explicit cuff authorization; synthetic tests, no captured-device interoperability confirmation |
| Standard BP Service `1810 / 2A35` | Implemented and parser-tested; other physical monitors untested |
| Omron Optional Service RACP | Implemented count/report and sequence-filter history; physical monitors untested |
| User Data Service | Implemented user consent/registration; physical monitors untested |
| Current Time / Omron Date Time | Implemented explicit writes on supported standard profiles; physical monitors untested |
| STP secure handshake | Independent Java implementation, standard crypto vectors and synthetic peer tests; physical monitors untested |
| Automatic X7 imports | Saved plain-WLP bonded X7 only; visible/notification/companion policies implemented and tested in software. Exact timing/lifecycle paths require phone/cuff checks. |
| X7 AFib | Separate nullable flag decoded from original EOSL map; comparison with monitor results still required |
| Health Connect | Optional one-bank BP write export, off by default; API/worker tests, provider permission/export flows need phone validation |
| Legacy vendor memory | HEM-7150T, HEM-7155T, HEM-7156T, HEM-7322T, HEM-7342T, HEM-7361T, HEM-7530T, HEM-7600T, HEM-6232T: model-bound key provisioning/unlock and read-only full-bank history; source-derived maps and synthetic tests, hardware-untested here |
| BP5360 vendor memory | HEM-7377T1: OS bond, single channel, distinct record starts and metadata user attribution; implemented and source-validated, hardware-untested here |
| Other WLP, WLP1COM or WLP4COM models | Unknown models and layouts rejected before memory commands |
| GLS_EX `504A / 504B` | Not decoded as BP: the inspected analyzer exposes glucose fields |
| ECG, pulse oximetry, scales, thermometers, HeartGuide activity | Outside this blood pressure implementation |
| Omron cloud, paid services and reminders | Not implemented |

Release 0.3.0 adds the vendor readers above. Added protocol implementations do not expand the set of hardware-tested monitors. Language availability does not imply a translation has been reviewed by a native speaker.

See [protocol selection](protocol.md#protocol-selection), [safety boundaries](safety.md) and [hardware validation](hardware-validation.md) before investigating another device. Unknown protocols and memory maps remain blocked rather than being inferred from a similar product name.

The [omblepy source comparison](omblepy-comparison.md) lists each record map and accepted identity. Added vendor paths do not implement EEPROM clock changes or unread-counter resets, unrestricted EEPROM dumps, or automatic imports. A complete standard BP/Optional Service profile retains priority when both layouts are exposed; mixed-profile devices need hardware validation.
