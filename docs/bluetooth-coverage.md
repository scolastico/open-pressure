# Bluetooth blood pressure coverage research

The target is every Bluetooth blood pressure monitor in OMRON Connect, including regional identities. Scales, thermometers, pulse oximeters and HeartGuide activity are outside this work. Complete's Bluetooth BP transfer is in scope; its audio ECG transfer is not. Camera and other non-Bluetooth transfers are excluded.

This is fixture-first research, not a declaration of new device support. The new identities below remain rejected by OpenPressure. Only the monitor and operations recorded in [compatibility](compatibility.md) are hardware-tested. A synthetic acknowledgment proves nothing about a physical cuff or the safety of a memory write.

## Reproduce the inventory

With a locally supplied package extracted as described in the [sandbox guide](omron-sandbox.md):

```sh
python3 tools/omron-bluetooth-coverage.py
python3 tools/test-omron-coverage.py
```

The first command writes `omron-sandbox/build/omron-sandbox/bluetooth-coverage.md`, an ignored local report of every enabled category-0 advertisement ID, its exact catalog identifier, config model/transport, indexed fixtures and opt-in strict scripts. It does not infer compatibility from a name or report scripts as executed results. The inspected 2026.09.03 (2026091010) catalog has **118 distinct primary/secondary BP advertisement IDs**. A zero secondary ID means no alternative ID. Repeated catalog rows are deduplicated by advertisement ID; conflicting identifiers fail the audit instead of silently hiding one.

Run the inventory again when replacing the package. Fixture presence covers only the advertised ID on that row, never all devices with the same marketed name. A config's `connect_type` is an asset label, not the runtime transport selector. Models exposing standard BP/Optional Service characteristics may work through that generic path without a dedicated memory fixture; absence from this report's fixture column is not a hardware failure.

## REA evidence and initial hypotheses

REA CLI inspection used the same base APK SHA-256 pinned in [protocol traceability](protocol.md#source-artifacts-and-traceability), `ce6550f7f9ecb52d488ced25fd421a6390ed5a239761784dc5f7510f088bab9d`. No original package, configuration, executable code or raw REA output is committed.

The exact REA target class is `jp.co.omron.healthcare.communicationlibrary.ohq.OHQBleDevice`; method `m` has one overload. REA 6.5.0 with the locally installed JADX 0.7.1 provider produced evidence ID `ev_87106cf90a196616810631b18d6a64f3a3f5e582c9c0e8dd413282e5c524f1b5`. Its observed selection rule is:

- `ohqMtuSize == 16`: WLP4COM, the legacy four-channel route.
- A different MTU: WLP1COM unless the manufacturer type is 8/9 or a saved application LTK exists, in which case WLP1COM_PAIR.

REA inspection of `jp.co.omron.healthcare.communicationlibrary.ohq.OHQWlCommand.k` produced `ev_476a9956bdd3e345b7810382320629b435c72df74923734befbff7be19594d9e`. It establishes eight-byte read frames, write-frame length `8 + payload length`, access opcode `01`, the write flag `C0`, the three-byte address, one-byte count, zero result byte and XOR check byte. The fixture post-transfer writes use that framing; this evidence does not authorize OpenPressure to transmit them.

The initial hypotheses combine that code observation with facts inspected in each exact model's `DeviceConfig.sys`, `OtherInfo.json` and `VitalDataIndexes.json`: channel count, setting/index reads, record locations, record width, byte order, date fields, pressure/pulse and checksums. No model is assigned another model's map merely because names or UUIDs resemble each other. Synthetic DIS strings are hypotheses too: a real device report is still needed to establish what each firmware returns.

The plain single-channel probes use manufacturer type 1, with the one-user flags and payload length matching; Pair sets the pairing-mode bit. This is not evidence that these physical monitors advertise plain mode. HEM-7188T1-LE and -LEO also have encrypted fresh-pair and saved-key probes: a one-user type-9 advertisement carries the exact catalog model ID, and the existing cuff-side `SecurePeer` checks the handshake and authenticated frames against Omron's real native Omsec library. The chosen advertisement types, synthetic peer and reserved bytes remain hypotheses about physical firmware.

## First fixture batch

Each identity has `connect-read-history` and `pair` scripts under its lowercase model directory in the shared [fixture root](../protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures/). The HEM-7188T1 identities also have `connect-encrypted` and `pair-encrypted`: **28 scripts for 12 identities** in this batch. Connect expects one decoded reading per BP user; Pair models the official registration flow separately. Fixture comments distinguish the independently constructed memory from observed official writes and invented acknowledgment echoes.

| Exact catalog identity | ID | Initial map hypothesis | BP slots per user |
| --- | --- | --- | --- |
| HEM-7151T-Z (BP5250) | `0160` | Four channels; 16-byte records at `0098` | 80 |
| HEM-7340T-Z (BP7350) | `0153` | Four channels; 16-byte records at `0098`, `0458` | 60 |
| HEM-7340T-CA (BP7350CAN) | `0161` | Four channels; 16-byte records at `0098`, `0458` | 60 |
| HEM-7341T-Z (BP5350) | `015E` | Four channels; 16-byte records at `0098`, `0458` | 60 |
| HEM-7343T-Z (BP5450) | `015F` | Four channels; 16-byte records at `0098`, `06D8` | 100 |
| HEM-6161T-E (RS3 Intelli IT) | `0255` | Four channels; swapped 14-byte records at `02E8` | 30 |
| HEM-6232T-D (RS7 regional identity) | `0244` | Four channels; swapped 14-byte records at `02E8`, `0860` | 100 |
| HEM-7143T1-E (M2 Intelli IT catalog variant) | `0288` | One channel, MTU parameter 64; 14-byte records at `02E8` | 30 |
| HEM-7143T1-D (M300 Intelli IT) | `0289` | One channel, MTU parameter 64; 14-byte records at `02E8` | 30 |
| HEM-7143T2-E (X2 Smart catalog variant) | `0287` | One channel, MTU parameter 64; 14-byte records at `02E8` | 30 |
| HEM-7188T1-LE (M2+ Connect) | `00DE` | One channel, MTU parameter 244; 16-byte records at `01C4` | 30 |
| HEM-7188T1-LEO (X2+ Connect) | `00F1` | One channel, MTU parameter 244; 16-byte records at `01C4` | 30 |

The four-channel probes expose index/setting reads and post-transfer writes independently. Omron clears the unsent count and changes birthday/clock settings after a transfer; OpenPressure must not acquire those writes as a consequence of adding read support. The fixtures explicitly require `expect ours rejected`. They are references for a later implementation, not an outgoing-command permit.

An `expect omron matches` directive makes a confirmed script a regression: a later divergence, refusal, missing operation or decoded-reading mismatch fails the Gradle sandbox test after saving its transcript. Omit it while exploring a new hypothesis. `-Domron.sandbox.explore=true` deliberately bypasses this assertion while retaining the reported outcome; replay again without exploration before calling a script matched.

### Recorded synthetic results, 2026-10-19

With OMRON Connect 2026.09.03 (2026091010), all **28 new scripts match in strict replay**: 24 plain Connect/Pair scripts and four encrypted HEM-7188T1 scripts. The selected sandbox run completed 30 JUnit tests including the strict/exploration contract checks, with no failures, errors or skips. These results confirm the complete scripted link exchange and Connect's expected decoded readings against the official library, not real monitor behavior.

All 28 new scripts are also replayed by the normal protocol suite and the real `MonitorService` test harness. The app refuses every new identity before issuing any characteristic write. The Android validation command `./gradlew --offline :protocol:check :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed; the app test run completed 914 tests without failures, errors or skips. The protocol, storage, locale and release fast checks and the inventory/command Python tests passed as well. No physical device was connected or tested for this work.

For a focused repeat through both clients:

```sh
tools/omron-sandbox.sh hem-7188t1-le/pair-encrypted
```

The expected comparison is Omron `matches` and OpenPressure `rejected`. Implementing these identities, rather than continuing to refuse them, is a separate next step requiring the model-specific parsing, bounds, transport and service tests described below.

## Remaining work toward full coverage

Use the local inventory's uncovered rows as the exact backlog, including regional and secondary IDs. Work remains for:

- Further WLS3 legacy maps and identities, including BP6350, Japanese/Asia-Pacific variants and newer revisions with the same marketed name.
- WLD1/2 single-channel models, including HEM-7146, HEM-7140/41/42/44, BP5150/BP7150/BP5255/BP7255 and the K4 revisions. A K4 catalog display name that resembles an accepted older identity must not authorize the older map.
- Newer WLD3 split/shared banks, including BP5465/BP7360/BP7465 and Canadian variants. Record attribution and settings offsets need independent fixtures.
- NightView HEM-9601T: its 24-byte records, 350-slot capacity and larger index/settings layout need their own decoder assumptions, not the ordinary 14/16-byte record templates.
- WLB/standard BP, Optional Service and STP monitors, including HeartGuide's BP readings. The current replay harness principally models WLP; these need service-appropriate peers and real service-orchestration tests before their coverage can be claimed.
- Encrypted variants, bank selection, full/wrapped/empty history, checksum/sequence failures, time-unavailable records, error responses and disconnect behavior for every newly implemented map.

After a complete synthetic session matches the official library, implement only evidenced, model-bound reads and parser behavior, with protocol and real `MonitorService` fixture tests. Verify negative identity/channel/security cases and preserve the [safety policy](safety.md). Hardware promotion remains separate under [hardware validation](hardware-validation.md).
