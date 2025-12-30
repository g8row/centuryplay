# AirPlay Protocol Documentation

> **Status**: Living document - Updated as we discover and verify protocol details
> **Last Updated**: 2025-12-30

## Overview

AirPlay is Apple's proprietary wireless media streaming protocol. There are two major versions:

| Version | Protocol Stack | Port | Discovery | Auth |
|---------|---------------|------|-----------|------|
| AirPlay 1 (RAOP) | RTSP + RTP | 5000-5005 | `_raop._tcp` | RSA (optional) |
| AirPlay 2 | HTTP + RTP/UDP | 7000 | `_airplay._tcp` | SRP-6a + HomeKit |

---

## AirPlay 1 (RAOP - Remote Audio Output Protocol)

### Discovery (mDNS/Bonjour)
- Service type: `_raop._tcp.local.`
- Name format: `<MAC>@<Device Name>`
- TXT record fields:
  - `am` - Airport Model
  - `cn` - Codec Name (0=PCM, 1=ALAC, 2=AAC, 3=AAC-ELD)
  - `et` - Encryption Types (0=none, 1=RSA, 3=FairPlay, 4=MFiSAP, 5=FairPlay SAPv2.5)
  - `md` - Metadata types supported
  - `sf` - Status Flags
  - `tp` - Transport Protocol (UDP, TCP)
  - `vn` - Protocol version
  - `vs` - Server version

### Protocol Flow (RTSP-based)
```
Client                                 Speaker
   |                                      |
   |------- OPTIONS ---------------------->| Query capabilities
   |<------ 200 OK (capabilities) --------|
   |                                      |
   |------- ANNOUNCE (SDP) --------------->| Describe audio format
   |<------ 200 OK -----------------------|
   |                                      |
   |------- SETUP ------------------------>| Negotiate ports
   |<------ 200 OK (Transport header) ----|
   |                                      |
   |------- RECORD ----------------------->| Start streaming
   |<------ 200 OK -----------------------|
   |                                      |
   |======= RTP Audio Packets ============>| (UDP, port from SETUP)
   |                                      |
   |<====== Timing Sync (NTP) ============| (UDP, timing port)
   |                                      |
   |------- SET_PARAMETER (volume) ------->| Control
   |<------ 200 OK -----------------------|
   |                                      |
   |------- TEARDOWN --------------------->| End session
   |<------ 200 OK -----------------------|
```

### Audio Format
- **Codec**: ALAC (Apple Lossless Audio Codec) - preferred
- **Alternative**: PCM (16-bit), AAC
- **Sample Rate**: 44100 Hz
- **Channels**: 2 (Stereo)
- **Frames per packet**: 352

### RTP Packet Structure
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           SSRC (Sync Source identifier)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                     ALAC Audio Data                           |
|                         (encrypted)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```
- **Payload Type (PT)**: 0x60 (96)
- **Marker (M)**: Set on first packet of stream
- **Timestamp**: Sample counter (increments by 352 per packet)

### Encryption
AirPlay 1 uses RSA + AES-128-CBC:
1. Client generates random 16-byte AES key and IV
2. AES key is encrypted with Apple's public RSA key
3. Encrypted key sent in ANNOUNCE SDP (`a=rsaaeskey`)
4. IV sent in ANNOUNCE SDP (`a=aesiv`)
5. Audio packets encrypted with AES-128-CBC

**Apple's RSA Public Key** (used in ANNOUNCE):
```
-----BEGIN RSA PUBLIC KEY-----
MIGJAoGBANVBj0pnWpE/cnKbFVME+8LT0Bup+cMzpzPjfEDopKoUWaIZsHPD
LjNFMQYY4MReFkA4g/CZz+wVEfMPAd0dNjJMSPapZ0HbV+A9pnwMpXPR4SZY
TkNj3RBnQ+ztYn6TkDZwNhF/jW0F9NV7P8nNJyZ5t5FXEk9NqY9E9b1ZNWZV
AgMBAAE=
-----END RSA PUBLIC KEY-----
```

### Timing Sync
NTP-like timing synchronization:
- Speaker sends timing requests to client's timing port
- Client responds with NTP timestamps
- Used to synchronize playback across multiple speakers

---

## AirPlay 2 

### Discovery (mDNS/Bonjour)
- Service type: `_airplay._tcp.local.`
- Port: 7000 (HTTP control)
- TXT record fields:
  - `deviceid` - Device ID (unique identifier)
  - `features` - Feature flags (hex string, see below)
  - `model` - Device model
  - `pi` - Pairing Identifier
  - `pk` - Ed25519 public key (base64)
  - `srcvers` - Source version
  - `vv` - Version verifier

### Feature Flags (Partial List)
| Bit | Feature |
|-----|---------|
| 0 | Video |
| 1 | Photo |
| 9 | Screen Mirroring |
| 14 | Audio |
| 17 | Supports Unified Pairing |
| 26 | Supports Buffered Audio |
| 27 | Supports PTP Clock |
| 30 | Supports HKPairingAndAccessControl |
| 38 | Supports SystemPairing |
| 40 | Authentication with APValeria |
| 46 | Supports CoreUtils Pairing and Encryption |
| 48 | Supports Buffered Audio |

### Authentication (HAP/HomeKit-based)

AirPlay 2 uses HomeKit Accessory Protocol (HAP) style authentication with SRP-6a:

#### Step 1: Pair-Setup (First time pairing)
```
 Client                               Speaker
    |                                    |
    |--- POST /pair-setup (M1) --------->|  TLV: State=1
    |<-- 200 OK (M2) --------------------| TLV: State=2, Salt, PublicKey
    |                                    |
    |--- POST /pair-setup (M3) --------->| TLV: State=3, PublicKey, Proof
    |<-- 200 OK (M4) --------------------|  TLV: State=4, Proof
    |                                    |
    |--- POST /pair-setup (M5) --------->| TLV: State=5, EncryptedData
    |<-- 200 OK (M6) --------------------|  TLV: State=6, EncryptedData
```

**SRP-6a Parameters (HAP-style)**:
- Group: 3072-bit RFC-5054 group
- Hash: SHA-512
- Username: `Pair-Setup` (literal string)
- Password: PIN shown on speaker (often 0000 for AirPlay)

**TLV8 Encoding**:
All pair-setup/verify messages use TLV8:
```
| Type (1 byte) | Length (1 byte) | Value (Length bytes) |
```

Common TLV types:
| Type | Name |
|------|------|
| 0x01 | Identifier |
| 0x02 | Salt |
| 0x03 | Public Key |
| 0x04 | Proof |
| 0x05 | Encrypted Data |
| 0x06 | State |
| 0x0A | Signature |
| 0x0B | Permissions |

#### Step 2: Pair-Verify (Session establishment)
```
 Client                               Speaker
    |                                    |
    |--- POST /pair-verify (M1) -------->| Curve25519 public key
    |<-- 200 OK (M2) --------------------| Curve25519 public key + encrypted proof
    |                                    |
    |--- POST /pair-verify (M3) -------->| Encrypted proof
    |<-- 200 OK (M4) --------------------|  Session established
```

After pair-verify, derive session keys using HKDF-SHA-512:
- Input key: Curve25519 shared secret
- Salt: `Pair-Verify-Encrypt-Salt`
- Info: `Pair-Verify-Encrypt-Info`

### Audio Streaming (After Authentication)

**SETUP Request** (HTTP):
```http
POST /setup HTTP/1.1
Content-Type: application/x-apple-binary-plist

{
    "streams": [{
        "type": 96,  // Buffered audio
        "audioFormat": 262144,  // ALAC 44100/16/2
        "controlPort": 0,
        "ct": 2,  // Compression type
        "shk": <shared audio key>,
        "spf": 352  // Samples per frame
    }],
    "timingProtocol": "PTP"
}
```

**Audio Transport**:
- AirPlay 2 can use buffered audio over UDP
- Uses PTP (Precision Time Protocol) instead of NTP for sync
- Supports multi-room synchronized playback

---

## Key Differences: v1 vs v2

| Aspect | AirPlay 1 | AirPlay 2 |
|--------|-----------|-----------|
| Control | RTSP (TCP) | HTTP (TCP) |
| Audio | RTP (UDP) | RTP/Buffered (UDP) |
| Discovery | `_raop._tcp` | `_airplay._tcp` |
| Auth | RSA (optional) | SRP-6a + HomeKit (required) |
| Timing | NTP-style | PTP |
| Multi-room | Limited | Native support |

---

## Implementation Status in This App

### Working
- [x] mDNS Discovery (`_airplay._tcp`)
- [x] AirPlay 1 RTSP handshake (OPTIONS, ANNOUNCE, SETUP, RECORD)
- [x] AirPlay 1 RTP audio streaming with AES encryption
- [x] AirPlay 2 Pair-Setup M1-M4 (partial)

### In Progress
- [ ] AirPlay 2 Pair-Setup M5-M6 (encrypted data exchange)
- [ ] AirPlay 2 Pair-Verify
- [ ] AirPlay 2 Audio streaming post-auth

### Not Started
- [ ] AirPlay 1 timing sync (NTP responder)
- [ ] Volume control during streaming
- [ ] Multi-room support

---

## Reference Sources

1. **shairport-sync** - Open source AirPlay 1/2 receiver
   - GitHub: https://github.com/mikebrady/shairport-sync
   - Most complete open-source implementation

2. **AirPlay2-Receiver (Python)** - AirPlay 2 receiver implementation
   - GitHub: https://github.com/openairplay/airplay2-receiver

3. **ap2-sender** - Python AirPlay 2 sender
   - GitHub: https://github.com/systemcrash/ap2-sender

4. **Unofficial AirPlay Protocol Spec**
   - http://nto.github.io/AirPlay.html (AirPlay 1)

5. **HAP-NodeJS** - HomeKit authentication reference
   - GitHub: https://github.com/homebridge/HAP-NodeJS

---

## Testing Notes

### Shairport-sync Docker Setup
```bash
docker run -d \
  --name shairport \
  --net host \
  -e AIRPLAY_NAME="Docker AirPlay" \
  mikebrady/shairport-sync
```

### Traffic Capture
```bash
# Capture AirPlay traffic on host
tcpdump -i any -w airplay.pcap port 7000 or port 5000-5010
```

### Known Issues
1. Some speakers require FairPlay authentication (not implemented)
2. HomePod requires full HomeKit pairing
3. DRM audio (Apple Music) may be blocked by AudioPlaybackCapture

