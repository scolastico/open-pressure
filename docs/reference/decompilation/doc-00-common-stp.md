# OMRON connect — STP Core: Connect → Pair → Encrypt → Time-sync

**App:** com.omronhealthcare.omronconnect 2026.09.03 (Android). **Method:** static only (JADX via REA + ELF inspection). Markings: **Observed** (cite `ev_`), **Inferred**, **Unknown**. Device identification/scan is covered in `doc-02-scan-identification.md` (cross-reference; not repeated here).

**Transport family:** OMRON **STP** = a proprietary application-layer secure channel layered on GATT. Two independent pieces live on the same connection:
1. A **custom-pairing / encryption handshake** on a proprietary service (`ecbe3980…` or 16-bit `fe4a`), which derives and returns a **Long-Term Key (LTK)** and turns on transparent **AES-128-CCM** for that service.
2. The actual data is read over **standard SIG profiles** (Blood Pressure 0x1810, Pulse-Oximeter 0x1822, Glucose 0x1808, Battery 0x180F) plus an OMRON **custom-command service 0x5045** (time-set, stored-record "GLS_EX" access).

Driver class: `jp.co.omron.healthcare.communicationlibrary.ohq.OHQBleStpDevice`. Pairing/encryption class: `ohq.OHQCustomPairing` (implements `ble.interfaces.BLECustomEncryptor`). The shared device/state-machine controller is `ohq.OHQBleDevice` (field `OHQBleStpDevice.f140928b`); its per-event GATT actions are the obfuscated `ohq.a.*` classes (IAction/IGuard implementations).

---

## 1. GATT role table (STP device)

| UUID | Enum (`BLEUuids`) | Role | Marking |
|------|-------------------|------|---------|
| `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b` | Service **J** | Proprietary **encrypted** service (primary). Device exposes J **or** K, exactly one. | Observed ev_04f2e0c5 |
| `0000fe4a-0000-1000-8000-00805f9b34fb` | Service **K** | Proprietary encrypted service (16-bit alias). | Observed ev_04f2e0c5 |
| `b305b680-aee7-11e1-a730-0002a5d5c51b` | Char **y2** | **Handshake control point**: handshake PDUs are **written here (TX)** and the responses are **notified back here (RX)**. Same characteristic both directions. | Observed ev_e1344efa (s6), ev_c3d2720a (j6) |
| `00005045-0000-1000-8000-00805f9b34fb` | Service **N** | OMRON **custom-command service** ("GLS_EX"): time-set + stored-record access. | Observed ev_2e8f7ab7 (y0), ev_f9d8793d (r7) |
| `0000504A-0000-1000-8000-00805f9b34fb` | Char **P2** | GLS_EX **notify/response** channel (wrapper subscribes here). | Observed ev_f9d8793d (r7 ctor) |
| `0000504B-0000-1000-8000-00805f9b34fb` | Char **Q2** | GLS_EX second characteristic. | Observed (enum), role Inferred |
| `00002A08-0000-1000-8000-00805f9b34fb` | Char **R2** | GLS_EX **date-time write** target. | Observed ev_2e8f7ab7 (y0) |
| `00002A52-0000-1000-8000-00805f9b34fb` | Char **D1** | SIG **RACP** (Record Access Control Point) for stored-record count/delete. | Observed (enum) + flow Inferred |
| `00002902` | Descriptor **c** | **CCCD** — written to enable notify/indicate. | Observed (strings) |

Measurement characteristics live on **standard SIG services**, not inside service J (see §6 and the BP/PLX docs).

**MTU:** The library requests **MTU = 243** immediately after `STATE_CONNECTED`, before service discovery. (Observed: string `"STATE_CONNECTED, requestMtu(243)"`; also `ohqMtuSize`.) Effective per-PDU payload is further bounded by native `nativeOmsecGetMaxCommandSize/MaxDataSize/PacketHeaderSize` for encrypted writes. (Observed: OHQCustomPairing.n/o/p → those native calls.)

---

## 2. Connection bring-up sequence (Observed primitives, order Inferred)

1. `BluetoothDevice.connectGatt(...)` → `STATE_CONNECTED`. (Observed `CONNECT_STATE_CONNECTED`)
2. `gatt.requestMtu(243)` → wait `EVT_ON_MTU_CHANGED`. (Observed)
3. `gatt.discoverServices()` (with retry/timeout: `bleDiscoverServicesRetryInterval`, `bleAfterDiscoverServicesTimeout`). (Observed)
4. For each needed characteristic: `setCharacteristicNotification(true)` + write **CCCD 0x2902** = `ENABLE_NOTIFICATION_VALUE` (0x0001) or `ENABLE_INDICATION_VALUE` (0x0002). There is an `internalWriteDescriptorWorkaround()`. (Observed strings)
5. Run the custom-pairing handshake (§3) if the device exposes service J/K.

GATT helper methods on `BLEDevice`/controller (naming from decompiled call sites):
- `.b(service, char, boolean, …)` = register characteristic + enable its CCCD. (Observed: d7/r7 ctors)
- `.c(service, char, bytes, cb)` = write (used for GLS_EX time-set). (Observed ev_2e8f7ab7)
- `.I(service, char, bytes, writeType)` = write (handshake; `writeType=0`). (Observed ev_e1344efa)
- `.k(service, char, bytes, cb)` / `.J(..., writeType, cb)` = WLP-path writes (not STP). (Observed, other family)
- `.G(callback)` on the `BLEDevice` = install the custom encryptor + turn on notifications for the handshake. (Observed ev_04f2e0c5, p6)

---

## 3. Handshake state machine (`OHQCustomPairing.c`, ev_55e7384a)

States/transitions (handler field → `ohq.a.*` action class resolved by tracing):

```
IDLE
  --CMD_START_ENCRYPTION / r6(save OHQOnStartEncryptionCallback)--> CUSTOM_PAIRING(entry=p6)
CUSTOM_PAIRING(entry p6):
    nativeOmsecInitialize(storedLtk|null) -> handle (f141048e)
    pick active service = whichever of {J,K} the device has  (f141051h)
    device.G(encryptor) -> enable notifications on control char y2
  choice BRANCH_HAS_LTK  (guard l6: storedLtk != null ?)
    ├─ NO  LTK -> SENDING_PAIRING_REQ(entry s6)
    └─ YES LTK -> SENDING_START_ENCRYPTION_REQ(entry t6)   (skips pairing req)
SENDING_PAIRING_REQ(s6)          --EVT_ON_CHARACTERISTIC_CHANGED[guard j6]--> SENDING_START_ENCRYPTION_REQ(t6)
SENDING_START_ENCRYPTION_REQ(t6) --EVT_ON_CHARACTERISTIC_CHANGED[guard j6]--> SENDING_CHALLENGE_REQ(u6)
SENDING_CHALLENGE_REQ(u6)        --EVT_ON_CHARACTERISTIC_CHANGED[guard j6]--> GETTING_LTK(v6)
GETTING_LTK(v6): nativeOmsecGetLtk -> store LTK -> r("EVT_END_ENCRYPTION")
  --EVT_END_ENCRYPTION--> ENCRYPTED(entry e6: set encrypted flag, return LTK to app)
(any state) EVT_CRYPTO_ERROR / EVT_ON_WRITE error --> IDLE (o6: nativeOmsecShutdown, clear flag; h6: report error)
  TIMEOUT_PAIRING_REQUEST guards SENDING_PAIRING_REQ.
```

Each handshake write to control char **y2** has the shape **`[0x70] ++ nativeOmsec…Req(body)`** — a one-byte opcode `0x70` (112) is prepended in Java, the rest comes from native (opaque). Each response arrives as a **notification on y2**; Java strips **byte[0]** (the response opcode) and feeds the remainder to the next native call. (Observed ev_e1344efa/ev_11999be4/ev_bc4edae1/ev_dd52f47b.)

Response-opcode checks (guards/actions):
- `g6` (ev_7d1cb8f2): on write-complete, if response **byte[1] == 0x26 (38)** → error 49 "AlreadyJustWorks"; else **byte[1] != 0** → error 48 `CustomPairingFault(byte[1])`. So **byte[1] is a pairing status code** (0 = OK).
- `j6`/`k6` (ev_c3d2720a/ev_49ebedb7): accept the notification only when it is on char y2 and (for one branch) **byte[0] == 0xF0** or **0xFF**. So the response opcode byte lives at byte[0]; `0xF0`/`0xFF` mark specific response classes. (Exact opcode table is inside native — Unknown.)

### Message exchange & native returns

| Step | State/action | Native call (opaque) | Writes to | Return |
|------|--------------|----------------------|-----------|--------|
| 1 | CUSTOM_PAIRING / p6 | `nativeOmsecInitialize(ltk\|null)` | — | `long` handle |
| 2 | SENDING_PAIRING_REQ / s6 | `nativeOmsecGetPairingReq(handle)` | y2: `0x70`+body | req bytes (ECDH pub + params, Inferred) |
| 3 | SENDING_START_ENCRYPTION_REQ / t6 | `nativeOmsecGetStartEncReq(handle, resp\|null)` | y2: `0x70`+body | req bytes |
| 4 | SENDING_CHALLENGE_REQ / u6 | `nativeOmsecGetChallengeReq(handle, resp)` | y2: `0x70`+body | req bytes (CMAC confirm, Inferred) |
| 5 | GETTING_LTK / v6 | `nativeOmsecGetLtk(handle, resp)` | — | **LTK bytes** → stored in `f141049f`, returned to app |
| — | ENCRYPTED / e6 | — | — | app callback `OHQOnStartEncryptionCallback.a(ltk, ok)` |

With a **stored LTK** (reconnect/bonded), the choice jumps to step 3 (start-encryption-req built with `resp=null`), then 4, then 5. (Observed: choice `l(A, state5, …, state4, …)`.)

**Bytes assembled in Java vs native:**
- Java only: the 1-byte `0x70` write prefix; stripping of the 1-byte response opcode; the pairing-status check on `byte[1]`.
- Native (black box): every request body and the LTK; also all CCM encrypt/decrypt.

---

## 4. Transparent encryption (`OHQCustomPairing.g`/`h`)

Once `f141050g` (encrypted flag) is set, **and only for characteristics whose service is J or K**:
- Outbound: `h(char, data)` → `nativeOmsecAesEncrypt(handle, data)` before the GATT write. (Observed ev_2729089d)
- Inbound: `g(char, data)` → `nativeOmsecAesDecrypt(handle, data)` on each notification. (Observed, decompiled)

**Scope caveat (Observed fact, interpretation Inferred):** the Java encrypt/decrypt filter triggers *only* when `service == J || service == K`. The standard SIG measurement services (0x1810/0x1822/0x1808/0x180F) and the 0x5045 command service are **not** routed through `nativeOmsecAesEncrypt/Decrypt`. Their confidentiality therefore relies on the bond established by the handshake (the derived LTK), not on this app-layer CCM. Whether the LTK is also installed as the BLE link key is **Unknown** (not resolvable statically). → Open gap.

**Crypto primitives** (from `libnativeOmsec.so`, arm64, `readelf`/strings — see report-ble.md §3): micro-ecc **NIST P-256 ECDH** (`EccPoint_compute_public_key`, `omsec_hal_generate_dhkey`), **AES-CMAC** (`omsec_hal_cmac`) key derivation, **AES-128-CCM** (`tc_ccm_config`, `omsec_hal_ccm_start/encrypt/decrypt`). RNG from `/dev/urandom`. No hardcoded keys in Java; key material = per-device LTK + fresh ECDH keypairs. Packet header layout, CCM nonce construction, and associated-data are **inside the .so only (black box)**.

---

## 5. Time sync (common to all STP devices)

`OHQBleStpDevice.d(Time, listener)` / `.n(Time, listener)` → posts **`EVT_GLS_EX_SET_DATE_TIME`** (Observed ev_960770d2). Action class **`ohq.a.y0`** (ev_2e8f7ab7) writes, via the GLS_EX wrapper, to **Service N (0x5045) / Char R2 (0x2A08)** with the payload from `GlucoseCustomDateTimeAnalyzer.a(time)` (Observed ev_96e0991f):

| Offset | Bytes | Field | Encoding |
|-------:|------:|-------|----------|
| 0 | 2 | Year | uint16 **little-endian** (`year%256`, `year/256`) |
| 2 | 1 | Month | `time.month + 1` (Java month is 0-based → 1–12 on the wire) |
| 3 | 1 | Day of month | uint8 |
| 4 | 1 | Hour | uint8 (0–23) |
| 5 | 1 | Minute | uint8 |
| 6 | 1 | Second | uint8 |

Total **7 bytes**. No weekday, no fractions. (The SIG 7-byte Date Time read path, `DateTimeAnalyzer.a`, ev_b02514bf, uses the identical layout; weekday is tracked separately in `OHQBleStpDevice.d` but not placed in this buffer.)

---

## 6. Minimum steps to establish an encrypted STP session (checklist)

1. **Identify** the device (doc-02) and confirm it advertises/contains service **`ecbe3980…`** or **`fe4a`**. If absent → it is a plain-SIG or WLP device, no STP handshake.
2. GATT **connect**.
3. **`requestMtu(243)`**, await response.
4. **`discoverServices()`**.
5. Enable **CCCD (0x2902 → 0x0001)** on control char **`b305b680…`** (y2) in the proprietary service.
6. `handle = nativeOmsecInitialize(storedLtk or null)`.
7. **First pairing (no LTK):** put the device in pairing mode (user action on device), then:
   - write `0x70 ++ nativeOmsecGetPairingReq(handle)` → y2; await notify `R`; `R'=R[1:]`.
   - write `0x70 ++ nativeOmsecGetStartEncReq(handle, R')` → y2; await notify; strip byte0.
   - write `0x70 ++ nativeOmsecGetChallengeReq(handle, …)` → y2; await notify; strip byte0.
   - `ltk = nativeOmsecGetLtk(handle, …)` → **persist `ltk` per device**.
   **Reconnect (have LTK):** skip the pairing-req; start at `nativeOmsecGetStartEncReq(handle, null)`, then challenge, then getLtk.
8. Verify each response: `byte[1] == 0` (0x26 = "already just works", any other non-zero = `CustomPairingFault`).
9. Session is **ENCRYPTED**: for every write/notify on a service-J/K characteristic, wrap/unwrap with `nativeOmsecAesEncrypt/Decrypt(handle, …)`.
10. Proceed to time-sync (§5) and measurement/RACP reads (BP/PLX docs).

### JNI calls a reimplementation MUST have (the black boxes)

Either link `libnativeOmsec.so` or reimplement the `Omsec` protocol. Required entry points (all on `ohq.OHQCustomPairing`, all opaque):

- `nativeOmsecInitialize(byte[] ltk) → long`  — start session, load/generate keys.
- `nativeOmsecGetPairingReq(long) → byte[]`  — first-pairing request (P-256 pubkey exchange).
- `nativeOmsecGetStartEncReq(long, byte[] resp) → byte[]`.
- `nativeOmsecGetChallengeReq(long, byte[] resp) → byte[]`  — CMAC challenge/confirm.
- `nativeOmsecGetLtk(long, byte[] resp) → byte[]`  — derive & return the LTK to persist.
- `nativeOmsecAesEncrypt(long, byte[]) → byte[]` / `nativeOmsecAesDecrypt(long, byte[]) → byte[]`  — AES-128-CCM framing (nonce + adata + MIC, all internal).
- `nativeOmsecGetMaxCommandSize()/MaxDataSize()/PacketHeaderSize() → int`  — sizing for encrypted writes.
- `nativeOmsecShutdown(long)`.

Underlying math to reproduce if reimplementing: **ECDH P-256 → AES-CMAC KDF → AES-128-CCM**. The exact message formats, nonce scheme, and KDF inputs are **not** present in Java and must come from reversing `lib/arm64-v8a/libnativeOmsec.so` (functions `Omsec::startBonding/startEncryption/packetIn/packetOut`, `omsec_hal_*`).

---

## Open gaps
- Exact byte layout of each handshake request/response body and the CCM nonce/adata (inside `libnativeOmsec.so`).
- Full response-opcode table on y2 (only `0xF0`/`0xFF` at byte0 and the status at byte1 are observed).
- Whether the derived LTK is also installed as the BLE link-layer key (affects whether SIG-service traffic is link-encrypted). Not statically determinable.
- Role of GLS_EX char Q2 (0x504B) and the P2 (0x504A) request/response framing for stored records.
- Whether service K (`fe4a`) devices differ from J (`ecbe3980`) beyond the UUID (handshake code treats them identically).
