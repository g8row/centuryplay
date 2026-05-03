# fairplay sapv2 handshake — research notes

> **status**: reverse-engineering research, not a complete implementation guide
> **last updated**: may 2026
> **source**: captures from macOS music → samsung airscreen, openairplay spec, community research

this document collects everything known about the `POST /fp-setup` handshake that receivers advertising `et=5` (fairplay sapv2) require before accepting `ANNOUNCE`.

---

## table of contents

1. [background](#background)
2. [where fairplay appears](#where-fairplay-appears)
3. [connection flow with fp-setup](#connection-flow-with-fp-setup)
4. [fply binary format](#fply-binary-format)
5. [phase 1 request](#phase-1-request)
6. [phase 1 response (142 bytes)](#phase-1-response-142-bytes)
7. [phase 2 request (164 bytes)](#phase-2-request-164-bytes)
8. [phase 2 response (32 bytes)](#phase-2-response-32-bytes)
9. [fpaeskey in announce](#fpaeskey-in-announce)
10. [fpaeskey binary layout](#fpaeskey-binary-layout)
11. [what the sender must compute](#what-the-sender-must-compute)
12. [error response format](#error-response-format)
13. [known implementations](#known-implementations)
14. [implementation paths](#implementation-paths)
15. [references](#references)

---

## background

fairplay sapv2 ("session authentication protocol v2") is apple's drm handshake that gates access to raop receivers requiring `et=5`. it is entirely proprietary and not documented by apple. everything here is derived from traffic captures and reverse-engineering by the community.

the sender (`airtunesd` on macos, `airplay.c` in ios) performs the handshake using secret sender-side fairplay credentials before the normal `ANNOUNCE → SETUP → RECORD` sequence.

---

## where fairplay appears

in the _raop txt record_ the `et` field declares supported encryption types:

| `et` value | meaning |
|-----------|---------|
| `et=0` | no encryption (clear) |
| `et=1` | rsa aes key exchange (classic raop, `a=rsaaeskey`) |
| `et=3` | fairplay sapv1 (older apple tv) |
| `et=5` | fairplay sapv2 (modern; required by samsung airscreen, newer receivers) |

when only `et=5` is advertised (without `et=1`), classic raop senders cannot stream. the receiver will hang at `ANNOUNCE` or actively reject after the fairplay handshake.

---

## connection flow with fp-setup

macOS music uses **two separate tcp connections** to the raop port (5000):

```
connection 1:
  POST /fp-setup   phase 1 (16-byte body)   →
                   phase 1 response (142 bytes) ←
  POST /fp-setup   phase 2 (164-byte body)  →
                   phase 2 response (32 bytes)  ←
  OPTIONS          (capabilities check)     →
                   200 OK                        ←
  [connection closed]

connection 2 (full streaming session):
  POST /fp-setup   phase 1                  →
                   phase 1 response (142)   ←
  POST /fp-setup   phase 2                  →
                   phase 2 response (32)    ←
  ANNOUNCE         AppleLossless + a=fpaeskey + a=aesiv →
                   200 OK                        ←
  SETUP            port negotiation         →
                   200 OK (ports)                ←
  RECORD           start streaming          →
                   200 OK                        ←
  ... audio rtp ...
```

> **note:** the fp-setup handshake is repeated on every new tcp connection. connection 1 appears to be a capability/sanity check only.

---

## fply binary format

all fairplay messages share a common binary envelope with a 4-byte `FPLY` magic at offset 0.

### common header (12 bytes)

```
Offset  Size  Field
------  ----  -----
0       4     magic      = 0x46 0x50 0x4c 0x59 = "FPLY"
4       1     fp_version (1 = SAPv1/classic, 2 = SAPv2)
5       1     message_type
6       1     subtype / mode
7       1     padding = 0x00
8       4     payload_length  (big-endian, bytes after offset 12)
```

the `fp_version`, `message_type`, and `subtype` together identify which step of the handshake is being performed.

---

## phase 1 request

sent by the **sender** (macos/ios) to the receiver.

### observed bytes (from macOS Music → Samsung AirScreen capture)

```
46 50 4c 59 02 01 01 00  00 00 00 04  02 00 03 bb
```

### field breakdown (16 bytes)

```
[0:4]   FPLY magic
[4]     0x02  fp_version = 2 (SAPv2)
[5]     0x01  message_type = 1 (phase 1 request)
[6]     0x01  subtype = 1
[7]     0x00  padding
[8:12]  0x00000004 = 4  (payload length = 4 bytes follow)
[12]    0x02  device/key selector (value = 2 observed)
[13]    0x00  padding
[14:16] 0x03bb = 955  (sequence or nonce seed, big-endian)
```

the `0x03bb` value (955) is consistent across captures from macos → airscreen. it may be a fixed protocol constant or a sequence number for this version/device combination.

the **content-type** used for `/fp-setup` requests is:
```
Content-Type: application/octet-stream
```

---

## phase 1 response (142 bytes)

returned by the **receiver** (airscreen) after a valid phase 1 request.

- 142 bytes total
- starts with `FPLY` magic
- contains the receiver's challenge / cryptographic material for phase 2
- exact internal structure is opaque — it is the input to the sender's fairplay crypto module to produce the phase 2 message

samsung airscreen returns a 142-byte phase 1 response only to well-formed phase 1 requests. the body is the cryptographic challenge the sender must respond to in phase 2.

---

## phase 2 request (164 bytes)

sent by the **sender** after processing the 142-byte phase 1 response.

- 164 bytes total
- starts with `FPLY` magic
- computed by the sender's fairplay module using:
  - the phase 1 response
  - a secret sender credential (private key / certificate embedded in `airtunesd` / apple's secure enclave)
- **cannot be reproduced** without the sender-side fairplay private key

### why dummy phase 2 is rejected

samsung airscreen validates the cryptographic content of the 164-byte phase 2 body. a random 164-byte payload triggers a 12-byte error response instead:

```
1e 1e 1e 1e  02 01 04 9c  00 00 00 00
```

field interpretation:
```
[0:4]   0x1e1e1e1e  error magic (not "FPLY")
[4]     0x02        fp_version = 2
[5]     0x01        message_type
[6]     0x04        subtype = 4 (error / rejection)
[7]     0x9c        error code = 156 (authentication failure)
[8:12]  0x00000000  zero payload
```

---

## phase 2 response (32 bytes)

returned by the **receiver** after a **valid** phase 2 message:

- 32 bytes total
- contains session key material the sender uses to derive `a=fpaeskey`
- exact structure is opaque

---

## fpaeskey in announce

after a successful fp-setup, the sender puts a `a=fpaeskey` attribute in the sdp body of `ANNOUNCE` instead of `a=rsaaeskey`.

```
ANNOUNCE rtsp://<ip>/<session> RTSP/1.0
...
Content-Type: application/sdp

v=0
o=iTunes <session_id> 0 IN IP4 <sender_ip>
s=iTunes
c=IN IP4 <receiver_ip>
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 AppleLossless
a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
a=fpaeskey:<base64>
a=aesiv:<base64>
```

the receiver decrypts `fpaeskey` using the session key material obtained during fp-setup (not using a static rsa key).

---

## fpaeskey binary layout

the `a=fpaeskey` value is base64-encoded. decoding reveals a **72-byte** FPLY-wrapped structure.

### observed examples (from openairplay community captures)

all three examples share an **identical 16-byte header**:
```
46 50 4c 59  01 02 01 00  00 00 00 3c  00 00 00 00
```

### full 72-byte layout

```
Offset  Size  Field
------  ----  -----
0       4     "FPLY" magic
4       1     0x01  fp_version = 1 (wrapped for SAPv1 receiver key)
5       1     0x02  message_type = 2 (key delivery)
6       1     0x01  subtype = 1 (AES key)
7       1     0x00  padding
8       4     0x0000003c = 60  (payload length = 60 bytes follow)
12      4     0x00000000  nonce or session id (0 = not used)
16      16    encrypted_block_1  (session-specific, encrypted AES material)
32      4     0x00000010 = 16  (length of the AES key field)
36      16    encrypted_aes_key  (the 16-byte AES-128 key, encrypted)
52      20    integrity_trailer  (authentication tag or padding)
```

the header at `[4:8] = 01 02 01 00` distinguishes this from the fp-setup phase 1 request `[4:8] = 02 01 01 00`:

| field | fp-setup phase1 | fpaeskey |
|-------|----------------|----------|
| byte 4 | `02` (SAPv2) | `01` (SAPv1 wrap) |
| byte 5 | `01` (phase 1) | `02` (key delivery) |
| byte 6 | `01`           | `01` |

the 16 bytes at `[36:52]` are the actual encrypted AES-128 session key. the receiver decrypts these using key material from the fp-setup handshake. after decryption, the recovered 16-byte key is used to decrypt rtp audio packets (same aes-128-cbc mechanism as in classic raop).

### hex examples

```
# Example 1 (iTunes/AppleLossless)
46504c59 01020100 0000003c 00000000   ← fixed header
f14e9cd7 becd66f9 fe7e0be4 a6641360   ← block1 (16 bytes, session-specific)
00000010                               ← AES key length = 16
943c7af6 b7937701 c5f4b68d 9a189151   ← encrypted AES key (16 bytes)
14c06dc2 f86eb600 71e02467 8f588ab5   ← trailer (20 bytes)
e6eb6378

# Example 2 (iOS/mpeg4-generic)
46504c59 01020100 0000003c 00000000
e1ba7386 8c74b917 017fa56e 3a7b1218
00000010
797e6ea8 6c989019 895fe81d e40344af
e6a623cb aba859af 70da3cee 94740571
89e1e2df
```

---

## what the sender must compute

to stream to an `et=5` receiver, the sender needs:

1. **a fairplay sender identity** — a private key / certificate bundle embedded in apple's `airtunesd` (or the secure enclave on device). this is what produces the valid 164-byte phase 2 body.

2. **phase 2 processing** — takes the 142-byte receiver challenge, applies the fairplay crypto function, and produces the 164-byte phase 2 request. this function is obfuscated ("white-box cryptography") inside `airtunesd`.

3. **fpaeskey construction** — after receiving the 32-byte phase 2 response, the sender:
   - generates a random 16-byte aes-128 session key
   - encrypts it using a key derived from the fp-setup exchange
   - wraps the result in the 72-byte FPLY envelope
   - base64-encodes it as `a=fpaeskey`

4. **audio encryption** — same as classic raop: aes-128-cbc, no iv chaining per packet, left-over bytes unencrypted. the only difference is the key is not rsa-wrapped but fp-wrapped.

### sender-side crypto chain summary

```
[fp-setup phase 1 request]  16 bytes  →  receiver
[fp-setup phase 1 response] 142 bytes ←  receiver

[fp-setup phase 2 request]  164 bytes →  receiver
  (computed from phase1 response + sender private key)
  
[fp-setup phase 2 response] 32 bytes  ←  receiver
  (contains session key material)
  
[derive session_key from phase2_response + sender_private_key]
[generate random aes_key (16 bytes)]
[fpaeskey = FPLY_wrap(encrypt(aes_key, session_key))]
[aesiv = random 16-byte iv]

ANNOUNCE sdp:
  a=rtpmap:96 AppleLossless
  a=fpaeskey:<base64(fpaeskey_72_bytes)>
  a=aesiv:<base64(aesiv_16_bytes)>
```

---

## error response format

a 12-byte rejection from the receiver:

```
Offset  Size  Value     Meaning
------  ----  --------  -------
0       4     1e1e1e1e  error magic
4       1     02        fp_version
5       1     01        message_type
6       1     04        error subtype
7       1     9c        error code (0x9c = 156 = authentication failure)
8       4     00000000  empty payload
```

this error is returned when:
- the phase 2 body is random/dummy (cryptographically invalid)
- the sequence/nonce doesn't match
- the sender credential is unrecognised

---

## known implementations

| project | direction | fp-setup | notes |
|---------|-----------|----------|-------|
| shairport-sync | receiver | partial | implements receiver-side decrypt of rsaaeskey; does NOT implement sender-side fairplay |
| UxPlay / RPiPlay | receiver | partial | handles receiver-side fp-setup for mirroring; not sender |
| openairplay/airplay2-receiver | receiver | yes (py) | receiver mode only |
| pyatv | sender+receiver | research | best reverse-engineering documentation of AirPlay 2 |
| centuryplay | **sender** | ❌ not yet | would need sender-side fairplay private key |

the critical gap: **almost no public project implements the sender side** of fp-setup (which requires the private fairplay credential that apple keeps secret inside `airtunesd`).

---

## implementation paths

three possible approaches to reach `et=5` receivers:

### option 1: extract and reuse `airtunesd` binary (not recommended)
- extract from macos/ios, call into it via jni bridge on android
- extremely fragile (binary changes with os updates), legal grey area
- `airtunesd` is arm64 on ios, x86_64 on macos; not android-compatible without emulation

### option 2: reverse-engineer the fairplay crypto function
- disassemble the fp-setup phase 2 computation in `airtunesd`
- the function is white-box obfuscated (apple uses a large, opaque switch-loop to hide the key operations)
- extremely difficult; requires professional binary analysis tooling (ida pro, ghidra)
- the obfuscation is deliberately designed to prevent clean re-implementation

### option 3: hardware mfi module (for commercial products)
- apple's mfi program provides a dedicated hardware chip that contains the private key
- the chip signs the challenge in hardware, returning the phase 2 response
- this is what legitimate "made for iphone" certified speakers and receivers use
- **not available** to indie developers

### option 4: focus on `et=1` receivers only (current centuryplay approach)
- detect `et=5` without `et=1` → show "FairPlay required" error
- stream perfectly to shairport-sync and other `et=1` receivers
- correct and legally safe, at the cost of not supporting receivers like samsung airscreen

---

## references

- [unofficial airplay spec — POST /fp-setup](https://openairplay.github.io/airplay-spec/audio/rtsp_requests/post_fp_setup.html)
- [unofficial airplay spec — ANNOUNCE](https://openairplay.github.io/airplay-spec/audio/rtsp_requests/announce.html)
- [openairplay/airplay2-receiver (python)](https://github.com/openairplay/airplay2-receiver)
- [FDH2/UxPlay (receiver with fp-setup)](https://github.com/FDH2/UxPlay)
- [FD-/RPiPlay (receiver)](https://github.com/FD-/RPiPlay)
- [pyatv — best modern AirPlay analysis](https://github.com/postlund/pyatv)
- `docs/AIRPLAY_PROTOCOL.md` — current centuryplay implementation notes
- `docs/AIRPLAY2_PROTOCOL.md` — AirPlay 2 / HAP notes
