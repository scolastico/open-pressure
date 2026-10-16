# OMRON connect — WLP ("WellnessLink") command protocol

**Transport family:** WLP — Omron proprietary "WellnessLink" command protocol over GATT, carried on the
proprietary service **J = `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b`** (all WLP characteristics share the
`…-0002a5d5c51b` WellnessLink base). This is the legacy stack used by older scales, BP monitors and
thermometers (BCM-500, HBF-*, HEM-6232T, MC-* …). It is **separate** from:
- **STP** (encrypted command channel, same service J) — see `doc-00-common-stp.md`.
- **SIG profiles** (standard 0x181D/0x181B/0x1810…) parsed by the `jp.co.ohq.ble` SDK — see
  `doc-weight-body-composition.md` and `doc-02-scan-identification.md`.

Markings: **Observed** = decompiled evidence (ev_ id); **Inferred**; **Unknown**.

Code: `jp.co.omron.healthcare.communicationlibrary.ohq.OHQWlCommand` (framing),
`ohq.OHQBleWlpDevice` / `ohq.OHQWlpDevice` (transport driver),
`ohq.constant.OHQProtocol` (family selector).

---

## 1. Protocol variants (OHQProtocol enum)

**Observed** (`ohq.constant.OHQProtocol.<clinit>`, ev_25cf128f; ordinals from smali):

| ordinal | name | chars used | encryption |
|--------:|------|-----------|-----------|
| 0 | `OHQLIB_PROTOCOL_WLP4COM` | 4 TX + 4 RX | clear |
| 1 | `OHQLIB_PROTOCOL_STP` | STP custom chars | AES-CCM (omsec) |
| 2 | `OHQLIB_PROTOCOL_WLP1COM` | 1 TX + 1 RX | clear |
| 3 | `OHQLIB_PROTOCOL_WLP1COM_PAIR` | 1 TX + 1 RX | **AES-CCM (omsec)** |
| 4 | `OHQLIB_PROTOCOL_WLP` | legacy generic | clear |

**WLP1COM vs WLP4COM** differ only in how many data characteristics the command/response payload is
striped across (1 vs 4). The command *framing* (below) is identical.

### Is WLP encrypted? (explicitly checked)

- **Plain WLP / WLP1COM / WLP4COM → sent in CLEAR.** The max-request-size calc
  `OHQBleWlpDevice.g()` (ev_db67085201a7…) computes the command budget as
  `F.size() * A` (number of TX chars × per-char bytes) with **no** omsec term; the omsec packet
  overhead is subtracted **only** when `protocol == OHQLIB_PROTOCOL_WLP1COM_PAIR`. If the omsec
  encryptor were on the write path for plain WLP, that budget would always be reduced. It is not.
- **WLP1COM_PAIR → AES-CCM encrypted (omsec), same crypto as STP.** `g()` subtracts
  `OHQCustomPairing.p()` = `nativeOmsecGetPacketHeaderSize()` (ev_182cb6f0) and caps the payload at
  `OHQCustomPairing.o()` = `nativeOmsecGetMaxDataSize()` (ev_91b12230). I.e. a WLP1COM_PAIR device
  speaks the **WLP command grammar tunnelled inside the STP/omsec AES-128-CCM channel** (ECDH P-256 →
  CMAC → CCM; see `doc-00-common-stp.md`). This is how newer "WLP"-tagged devices (e.g. HEM-6232T,
  HEM-7530T) combine the legacy command set with modern link encryption.
- **Inferred:** OS/BLE bonding is not required for plain WLP (legacy WellnessLink devices predate LESC);
  WLP1COM_PAIR performs the omsec app-level pairing (LTK persisted) exactly like STP.

---

## 2. Command frame (WL command)

Every WL command/response is a single compact frame (commands are small — ≤ `g()` bytes, and the
"sys command" path caps at **20 bytes = one ATT write**, ev_8ce1a462). The frame:

```
            +------+------+---------- … ----------+--------+------+
  offset    |  0   |  1   | 2 … (header+payload)  |  n-2   | n-1  |
            +------+------+---------- … ----------+--------+------+
  field     | LEN  | CMD  | params / data         | STATUS | BCC  |
            +------+------+-----------------------+--------+------+
  LEN    = total frame length in bytes (= n)           byte[0]
  CMD    = command/opcode (request). Response echoes CMD | 0x80. byte[1]
  STATUS = result/status code (0 on request)           byte[n-2]
  BCC    = XOR of bytes[0 .. n-2]  (checksum)           byte[n-1]
```

- **Response = request opcode with bit7 set.** Confirmed by the clinit templates
  `g = {08,01,00,00,00,00}` (request cmd 0x01) and `h = {08,81,00,00,00,00}` (response cmd 0x81)
  (OHQWlCommand.<clinit>, ev_e26d0782), and by `checkReqCommandAndResCommand` (ev_5dd371e9) which sets
  `respCmdExpected = reqCmd | 0x80` before comparing.
- `OHQWlCommand.y(resp)` (ev_6b85aa5a) stores a response only if it passes `e()` matching; so a client
  must pair each response to its request by `(CMD|0x80)` + address bytes.

### 2.1 Checksum (BCC) — exact computation

**Observed** build `OHQWlCommand.a(byte[] b, int len)` (ev_1bca06b4):
```java
byte bcc = 0;
for (int i = 0; i < len; i++) bcc ^= b[i];   // XOR of all bytes BEFORE the BCC
```
Every builder calls `a(frame, n-1)` and writes the result to `frame[n-1]`. So **BCC = XOR of every
byte of the frame except the last**, stored as the last byte.
**Observed** verify `OHQWlCommand.d(byte[] b)` (ev_6e8f6e8b): recompute XOR of `b[0 .. len-2]`, compare
to `b[len-1]`. (Bytes are treated unsigned via `c()`, ev_c0939166.)

---

## 3. Command table (opcodes)

Opcode is `frame[1]`. Builders in `OHQWlCommand` (static). POI-aliased literals resolved:
`IntersectionPtg.sid = 0x0F`, `UnionPtg.sid = 0x10`, `RangePtg.sid = 0x11`.

| CMD | sub | builder (ev) | meaning | request layout |
|----:|----:|--------------|---------|----------------|
| `0x01` | — | `k()` createSingleAccess (ev_eab0a4f2) | **Single memory read/write** — the main data command | see 3.1 |
| `0x03` | — | `l()` createSingleAccessEx (ev_835603c2) | **Extended memory access** (32-bit addr + 32-bit length) | see 3.2 |
| `0x00` | `0x10`/`0x11` | `j()` (ev_80acf31c) | Session/transaction control (sub 0x10 start / 0x11 end — `z5` toggles) | `08 00 00 00 00 {10|11} 00 BCC` |
| `0x0F` | — | `g()` (ev_458222d2) | Control w/ flag at [5] (0/1) | `08 0F 00 00 00 {00|01} 00 BCC` |
| `0x0F` | — | `i()` (ev_8d6a0d1e) | Control variant, params = `FF FF FF` | `08 0F FF FF FF {00|01} 00 BCC` |

*(CMD 0x00/0x0F exact semantics — e.g. time-set, model/version, streaming enable — are **Unknown** at
byte level; the `z5` boolean selects the two sub-values shown. The `0x01`/`0x03` memory commands are the
load-bearing ones for reading stored measurements.)*

### 3.1 CMD 0x01 — single memory access (read or write)

**Observed** `OHQWlCommand.k(addr3, data, dataLen, isRead)` (ev_eab0a4f2):
```
  byte[0] = LEN            // 8 (read) or 8 + data.length (write)
  byte[1] = 0x01
  byte[2] = addr[0] | rwFlag   // rwFlag = 0xC0 on WRITE, 0x00 on READ
  byte[3] = addr[1]
  byte[4] = addr[2]            // 24-bit memory address, big-endian in addr[0..2]
  byte[5] = dataLen            // number of data bytes to read / write (uint8)
  byte[6..] = data             // WRITE only
  byte[n-2] = 0x00
  byte[n-1] = BCC
```
- Address is 24-bit; **top two bits of addr[0] are the R/W flag** (`(addr[0] & 0xC0)` must be 0 in the
  caller-supplied address; `0xC0` is OR-ed in for a write). READ frame is fixed 8 bytes.
- On WRITE, `data.length` must equal `dataLen`.

### 3.2 CMD 0x03 — extended single access

**Observed** `OHQWlCommand.l(addr4, size4)` (ev_835603c2): fixed 12-byte frame
```
  12 03 A0 A1 A2 A3 S0 S1 S2 S3 00 BCC
     └─┘ └──────────┘ └──────────┘
    cmd  32-bit addr   32-bit size   (both big-endian, as supplied)
```

---

## 4. Response parsing / payload extraction

Request bytes held in `f141091e` (set by `x()`, ev_6e7c564f); validated response in `f141092f`
(set by `y()`, ev_6b85aa5a). Accessors:

| method (ev) | returns |
|-------------|---------|
| `o()` (ev_178e36cf) | response CMD byte `resp[1]` (= reqCmd\|0x80); 0xFF if none |
| `m()` (ev_e37ed7bd) | `resp[2]` |
| `u()` (ev_afe304e2) | `resp[3..4]` (2 bytes) |
| `s()` (ev_ac6b9511) | STATUS byte `resp[n-2]` |
| `p()` (ev_dc113435) | **extracted payload** (see below) |
| `q()`/`r()` | raw request / raw response |

**Observed** payload extractor `OHQWlCommand.p()` (ev_dc113435):
- **Non-extended responses** (req CMD ≠ 0x03): read `respLen = resp[0]`, `respCmd = resp[1]`.
  For `respCmd ∈ {0x80, 0x81, 0xB0}` the data is `resp[6 .. 6+(respLen-8)]` →
  **payload = bytes 6 … LEN-3, i.e. payload length = LEN − 8** (6-byte header + STATUS + BCC).
  (`0x81` = reply to the 0x01 memory read.)
- **Extended responses** (req CMD = 0x03): a **32-bit big-endian total length** occupies `resp[0..3]`,
  opcode marker `resp[4] == 0x83`; **payload = resp[13 … 13+(total-15)]** (15-byte extended header +
  trailer). Use this for large stored-record dumps.

---

## 5. Transport: characteristics, TX/RX, multi-char reassembly

Driver `OHQBleWlpDevice` (extends `OHQWlpDevice`). Key constructor defaults (`<init>`, ev_09c905aa):
`A = 16` (**bytes per characteristic**), `F = []` (TX list), `G = []` (RX list), `I = false`
(high-reliability/CRC32 flag).

### 5.1 WLP characteristic family (service J)

**Observed** `BLEUuids.Characteristic.<clinit>` (ev_ed0a3cd2):

| field | UUID |
|-------|------|
| y2 | `b305b680-aee7-11e1-a730-0002a5d5c51b` |
| z2 | `db5b55e0-aee7-11e1-965e-0002a5d5c51b` |
| A2 | `e0b8a060-aee7-11e1-92f4-0002a5d5c51b` |
| B2 | `0ae12b00-aee8-11e1-a192-0002a5d5c51b` |
| C2 | `10e1ba60-aee8-11e1-89e5-0002a5d5c51b` |
| D2 | `49123040-aee8-11e1-a74d-0002a5d5c51b` |
| E2 | `4d0bf320-aee8-11e1-a0d9-0002a5d5c51b` |
| F2 | `5128ce60-aee8-11e1-b84b-0002a5d5c51b` |
| G2 | `560f1420-aee8-11e1-8184-0002a5d5c51b` |
| I2 | `8858eb40-aee8-11e1-bb67-0002a5d5c51b` |

### 5.2 Directions and striping

- **TX (phone → device, Write):** the `F` list. Max command size = `F.size() * 16 − (I ? 6 : 0)`
  (`g()`, ev_db670852). WLP1COM: `|F| = 1` → 16 B; WLP4COM: `|F| = 4` → 64 B per command round.
- **RX (device → phone, Notify):** the `G` list. Responses arrive as `EVT_ON_CHARACTERISTIC_CHANGED`
  notifications (state `WAITING_TRANSACTION`, `OHQBleWlpDevice.m()`, ev_9aa4f100). Bytes per char are
  collected in map `f141038y` keyed by each `G` characteristic.
- **Reassembly** (`OHQBleWlpDevice.k()`, ev_c0a5b040): the device puts the **total length in byte[0]
  of the first RX char** (`G.get(0)`); number of characteristics used =
  `min( ceil(totalLen / chunkSize), G.size() )`; the full frame is the concatenation of the `G`
  characteristics' buffers in order. WLP1COM uses 1 char; WLP4COM up to 4.
- **Inferred (gap):** the exact assignment of the 10 UUIDs above into `F` vs `G` (and which single UUID
  is the control/command char, likely `8858eb40`/I2) is decided at service-discovery time inside the
  obfuscated `ohq`/`ble.a.*` state-action classes and was **not** traced to the write/notify call
  sites. Model: treat the aee7 group and aee8 group as the two directional banks; discover which carry
  the `Write` vs `Notify` GATT properties on the real device and map `F`/`G` accordingly.

### 5.3 High-reliability framing (optional, `I == true`)

**Observed** `OHQBleWlpDevice.o()` (ev_62205af9): when enabled, each transferred block is
`[2-byte header][payload][4-byte CRC32]` where CRC32 is **little-endian** (`java.util.zip.CRC32` over
`block[0 .. len-5]`). On RX the layer validates the trailing CRC32 and returns `block[2 .. len-5]`.
This is why `g()` subtracts 6 bytes (2 header + 4 CRC) when `I`. The inner WL command (§2) still carries
its own BCC.

---

## 6. Driver API surface (OHQWlpDevice / OHQBleWlpDevice)

| method (ev) | purpose |
|-------------|---------|
| `i(OHQWlCommand)` sendWlCommand (ev_656373fb) | queue/send a WL command; rejects if `req.length > g()`; fires `CMD_SEND_WL_COMMAND` |
| `e(OHQSysCommand)` sendSysCommand (ev_8ce1a462) | raw ≤20-byte "system" command; `CMD_SEND_SYS_COMMAND` |
| `b(cb, list)` getDeviceDetailsWithKey (ev_e85a9a16) | `CMD_GET_DEVICE_DETAILS` — device-details exchange keyed by an app-held key list (registration handshake) |
| `d(cb)` openStreaming (ev_2945ce3b) | `CMD_OPEN_STREAMING` |
| `a(cb)` / `f(cb)` (ev_4b40d3bf / ev_fa082c6c) | register WL-command / sys-command receive callbacks |
| `j()` clearReqCommandList (ev_cb07e27d) | `CMD_CLEAR_COMMAND_LIST` |
| `g()` (ev_db670852) | current max WL-command request size (see §1/§5) |

**Pairing/registration (Inferred):** plain WLP registers by exchanging a device "key" via
`getDeviceDetailsWithKey` (legacy WellnessLink per-user/device key stored in device memory; `h()` keeps
these in app SharedPreferences, ev_ea1af8c9). `WLP1COM_PAIR` adds the omsec app-level ECDH pairing and
persists an LTK exactly as STP does (`doc-00-common-stp.md`). No evidence of a hardcoded shared key in
Java.

---

## 7. Worked example — read a 32-byte block at address 0x001000 (plain WLP)

1. Build read command `k({0x00,0x10,0x00}, null, 32, true)` →
   `08 01 00 10 00 20 00 BCC` (BCC = XOR of first 7 bytes).
2. Write the 8 bytes to the TX characteristic (single ATT write; 8 ≤ 16).
3. Collect notifications on the RX char(s); first RX byte = total response length; reassemble (§5.2).
4. Verify `resp[1] == 0x81`, verify BCC (`d()`), check STATUS `resp[n-2] == 0`.
5. Payload = `resp[6 .. 6+(resp[0]-8)]` (`p()`), i.e. the 32 bytes read. Checksum covers all but last byte.

---

## Open gaps (Unknown)

- Exact `F`/`G` (TX vs RX) UUID assignment and the control-char identity among `y2…I2` (set in
  `ohq`/`ble.a.*` discovery actions; not traced).
- Semantics/byte layout of CMD `0x00` and `0x0F` sub-commands (time set? version? streaming enable?).
- Whether `getDeviceDetailsWithKey` performs a challenge/response or just reads an identity block.
- The device-side memory map (which addresses hold which records) — for scales see
  `doc-weight-body-composition.md` §4; addresses are model-specific and partly data-driven (OGSC).
