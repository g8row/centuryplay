# AirPlay 1 (RAOP) Protocol Documentation

> **Status**: Comprehensive reference based on reverse engineering and implementation
> **Last Updated**: January 2026

This document provides a complete reference for the AirPlay 1 protocol (also known as RAOP - Remote Audio Output Protocol), based on reverse engineering and implementation experience with shairport-sync receivers.

## Table of Contents

1. [Overview](#overview)
2. [Protocol Architecture](#protocol-architecture)
3. [RTSP Control Protocol](#rtsp-control-protocol)
4. [Session Establishment](#session-establishment)
5. [Audio Streaming](#audio-streaming)
6. [Time Synchronization](#time-synchronization)
7. [Encryption](#encryption)
8. [Troubleshooting](#troubleshooting)
9. [Quick Reference](#quick-reference)

---

## Overview

AirPlay 1 is Apple's proprietary audio streaming protocol, introduced circa 2004 as "AirTunes" and later renamed to AirPlay. It enables wireless audio streaming from a sender (client) to a receiver (speaker/server).

### Key Characteristics

| Property | Value |
|----------|-------|
| Control Protocol | RTSP (Real-Time Streaming Protocol) |
| Control Port | TCP 5000 (default) |
| Audio Transport | RTP over UDP |
| Audio Format | L16/44100/2 (16-bit PCM, 44.1kHz, stereo) or encrypted ALAC |
| Encryption | AES-128-CBC with RSA key exchange |
| Discovery | mDNS/Bonjour (`_raop._tcp`) |

### Protocol Flow Summary

```
Client                                    Server (Receiver)
   |                                           |
   |  ---- OPTIONS (capabilities) -----------> |
   |  <-------- 200 OK ----------------------- |
   |                                           |
   |  ---- ANNOUNCE (SDP with format) -------> |
   |  <-------- 200 OK ----------------------- |
   |                                           |
   |  ---- SETUP (port negotiation) ---------> |
   |  <-------- 200 OK (ports) --------------- |
   |                                           |
   |  ---- RECORD (start streaming) ---------> |
   |  <-------- 200 OK ----------------------- |
   |                                           |
   |  ==== UDP: Timing packets <==============>| (bidirectional)
   |  ==== UDP: Sync packets ================> | (client to server)
   |  ==== UDP: Audio RTP packets ==========> | (client to server)
   |                                           |
   |  ---- SET_PARAMETER (volume, etc) ------> |
   |  <-------- 200 OK ----------------------- |
   |                                           |
   |  ---- TEARDOWN (end session) -----------> |
   |  <-------- 200 OK ----------------------- |
```

---

## Protocol Architecture

### Network Ports

AirPlay 1 uses multiple ports for different purposes:

| Port Type | Protocol | Direction | Purpose |
|-----------|----------|-----------|---------|
| RTSP Control | TCP | Bidirectional | Session control, commands |
| Audio | UDP | Client → Server | RTP audio packets |
| Control | UDP | Client → Server | Sync/timing control packets |
| Timing | UDP | Bidirectional | NTP-like time synchronization |

**Default Port Ranges:**
- RTSP: TCP 5000
- UDP Base: 6001 (control), 6002 (timing), 6003 (audio)

### Client Identifiers

Each AirPlay client must provide several identifiers:

```
Client-Instance: 9910F4FAD33B72F1  (16 hex chars, random)
DACP-ID: 9910F4FAD33B72F1          (same as Client-Instance)
Active-Remote: 1478837813          (random 32-bit integer as string)
```

**Purpose:**
- `Client-Instance`: Unique identifier for this client session
- `DACP-ID`: Digital Audio Control Protocol ID for remote control
- `Active-Remote`: Used for bidirectional remote control communication

---

## RTSP Control Protocol

All RTSP messages follow this format:

### Request Format
```
METHOD rtsp://host/session RTSP/1.0
CSeq: <sequence_number>
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: <16_hex_chars>
DACP-ID: <16_hex_chars>
Active-Remote: <number>
[Additional Headers]

[Body if Content-Length > 0]
```

### Response Format
```
RTSP/1.0 <status_code> <reason>
CSeq: <matching_sequence_number>
Server: AirTunes/105.1
[Additional Headers]

[Body if Content-Length > 0]
```

### Common Status Codes
| Code | Meaning |
|------|---------|
| 200 | OK - Success |
| 400 | Bad Request |
| 453 | Not Enough Bandwidth |
| 501 | Not Implemented |

---

## Session Establishment

### Step 1: OPTIONS (Optional but Recommended)

Tests server connectivity and retrieves capabilities.

**Request:**
```
OPTIONS * RTSP/1.0
CSeq: 1
User-Agent: iTunes/10.6 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Apple-Challenge: lY5pmgHcGK2IJ8RnKnUb9w==
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 1
Server: AirTunes/105.1
Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER
Apple-Response: <base64_challenge_response>
```

**Apple-Challenge/Response:**
- Challenge: 16 random bytes, Base64 encoded
- Response: RSA signature proving server has the private key
- Used to verify authentic AirPlay receivers

### Step 2: ANNOUNCE

Describes the audio format and provides encryption keys.

**Request:**
```
ANNOUNCE rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 2
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Content-Type: application/sdp
Content-Length: 283
Apple-Challenge: CgDMMJoy4cm7gOLuUfnnQw==

v=0
o=iTunes 7981095476330793800 0 IN IP4 192.168.1.172
s=iTunes
c=IN IP4 192.168.1.220
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 L16/44100/2
a=rsaaeskey:<base64_encrypted_aes_key>
a=aesiv:<base64_iv>
```

**SDP Fields Explained:**

| Field | Meaning |
|-------|---------|
| `v=0` | SDP version |
| `o=iTunes <session_id> 0 IN IP4 <client_ip>` | Origin (sender info) |
| `s=iTunes` | Session name |
| `c=IN IP4 <server_ip>` | Connection (receiver IP) |
| `t=0 0` | Time (always 0 0 for streaming) |
| `m=audio 0 RTP/AVP 96` | Media line: audio, port 0 (negotiated later), RTP, payload type 96 |
| `a=rtpmap:96 L16/44100/2` | Payload 96 is L16, 44100Hz, 2 channels |
| `a=rsaaeskey:<key>` | RSA-encrypted AES key (if encrypted) |
| `a=aesiv:<iv>` | AES initialization vector (if encrypted) |

**Audio Format Options:**
- `L16/44100/2` - Uncompressed 16-bit PCM (simpler, higher bandwidth)
- `AppleLossless` - ALAC compressed (more complex, lower bandwidth)

### Step 3: SETUP

Negotiates UDP ports for audio, control, and timing.

**Request:**
```
SETUP rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 3
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=57099;timing_port=58571
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 3
Server: AirTunes/105.1
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002;server_port=6003
Session: 1
```

**Transport Header Parameters:**

| Parameter | Meaning |
|-----------|---------|
| `RTP/AVP/UDP` | RTP Audio/Video Profile over UDP |
| `unicast` | Point-to-point (not multicast) |
| `interleaved=0-1` | Channel interleaving |
| `mode=record` | Client is sending (not receiving) |
| `control_port` | UDP port for sync/control packets |
| `timing_port` | UDP port for time synchronization |
| `server_port` | Server's audio receive port |

### Step 4: RECORD

Starts the streaming session.

**Request:**
```
RECORD rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 4
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Session: 1
Range: npt=0-
RTP-Info: seq=10727;rtptime=4234197534
```

**RTP-Info Header:**
- `seq`: Starting RTP sequence number (16-bit, random initial value)
- `rtptime`: Starting RTP timestamp (32-bit, random initial value)

**Response:**
```
RTSP/1.0 200 OK
CSeq: 4
Server: AirTunes/105.1
Audio-Latency: 11025
```

**Audio-Latency:** Server's internal latency in samples (at 44100Hz)
- 11025 samples = 250ms

### Step 5: SET_PARAMETER (Optional)

Sets volume and other parameters during playback.

**Volume Request:**
```
SET_PARAMETER rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 5
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Content-Type: text/parameters
Content-Length: 14

volume: -6.000
```

**Volume Scale:**
- `-144.0` = Mute
- `-30.0` = Very quiet
- `0.0` = Maximum volume

### Step 6: TEARDOWN

Ends the session and releases resources.

**Request:**
```
TEARDOWN rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 6
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Session: 1
```

---

## Audio Streaming

### RTP Packet Format

Audio is sent as RTP packets over UDP to the server's audio port.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                              SSRC                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Audio Payload                         |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Header Fields (12 bytes):**

| Field | Bits | Value | Description |
|-------|------|-------|-------------|
| V | 2 | 2 | RTP version |
| P | 1 | 0 | Padding flag |
| X | 1 | 0 | Extension flag |
| CC | 4 | 0 | CSRC count |
| M | 1 | 0 | Marker bit |
| PT | 7 | 96 | Payload type (dynamic) |
| Sequence | 16 | varies | Packet sequence (wraps at 65535) |
| Timestamp | 32 | varies | RTP timestamp (samples) |
| SSRC | 32 | random | Synchronization source ID |

### L16 Audio Payload

For uncompressed PCM (L16/44100/2):

- **Byte Order:** Big-endian (network byte order) - **CRITICAL!**
- **Sample Size:** 16 bits per sample
- **Channels:** 2 (stereo, interleaved: L, R, L, R, ...)
- **Samples per Packet:** 352 frames (352 × 4 = 1408 bytes)
- **Sample Rate:** 44100 Hz

**Important:** Android's AudioRecord provides little-endian samples. You MUST convert to big-endian before sending!

```kotlin
// Swap bytes for 16-bit samples (little-endian → big-endian)
fun swapEndianness(data: ByteArray): ByteArray {
    val result = ByteArray(data.size)
    for (i in 0 until data.size step 2) {
        result[i] = data[i + 1]      // High byte first
        result[i + 1] = data[i]      // Low byte second
    }
    return result
}
```

### Timestamp Progression

The RTP timestamp increments by the number of audio frames per packet:

```
Initial timestamp: T₀ (random 32-bit value)
After packet 1: T₀ + 352
After packet 2: T₀ + 704
After packet N: T₀ + (N × 352)
```

Timestamp wraps at 2³² (4,294,967,296).

---

## Time Synchronization

Time sync is **CRITICAL** for audio playback. Without it, the receiver doesn't know when to play audio frames.

### NTP Timestamps

AirPlay uses NTP-style timestamps (64-bit):
- **Seconds:** 32-bit, seconds since January 1, 1900
- **Fraction:** 32-bit, fractional seconds (2³² = 1 second)

```kotlin
// Convert Unix milliseconds to NTP timestamp
val ntpSec = (unixMillis / 1000) + 2208988800L  // Unix epoch → NTP epoch
val ntpFrac = ((unixMillis % 1000) * 4294967296.0 / 1000.0).toLong()
```

**Epoch Difference:** NTP epoch is January 1, 1900. Unix epoch is January 1, 1970.
- Difference: 2,208,988,800 seconds (70 years)

### Timing Protocol (Port 6002)

The receiver sends timing requests, client must respond.

**Timing Request (32 bytes):**
```
Bytes 0-7:   Header
Bytes 8-15:  Origin timestamp (T₁) - filled on reply
Bytes 16-23: Receive timestamp (T₂) - filled on reply  
Bytes 24-31: Transmit timestamp (T₃) - when server sent request
```

**Timing Response (32 bytes):**
```
Byte 0:      0x80 (RTP version 2)
Byte 1:      0xD3 (0x53 | 0x80 = timing reply)
Bytes 2-7:   Copy from request
Bytes 8-15:  T_orig = copy T_xmit from request (bytes 24-31)
Bytes 16-23: T_recv = when we received the request
Bytes 24-31: T_xmit = when we're sending the reply
```

### Sync/Control Protocol (Port 6001)

The client MUST send sync packets to tell the receiver the RTP-to-NTP time mapping.

**Sync Packet (20 bytes):**
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|   PT=0xD4   |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Current RTP Timestamp                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                   NTP Timestamp (64-bit)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Next RTP Timestamp                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Fields:**
| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 1 | Header | 0x90 (first packet) or 0x80 (subsequent) |
| 1 | 1 | Type | 0xD4 (payload type 84 = sync) |
| 2 | 2 | Sequence | Sync packet sequence number |
| 4 | 4 | RTP Time | RTP timestamp to play at NTP time |
| 8 | 8 | NTP Time | When the RTP timestamp should play |
| 16 | 4 | Next RTP | Next sync point (RTP + latency) |

### Sync Timing Logic (CRITICAL!)

The sync packet answers: "When should the receiver play RTP timestamp X?"

**Latency Concept:**
- The receiver needs time to buffer audio before playback
- Standard latency: ~2.5 seconds (110,250 samples at 44100Hz)
- Sync tells receiver: "Play RTP timestamp X at time (NOW + latency)"

```kotlin
// CORRECT sync timing
val latencyMs = 2500L  // 2.5 seconds
val latencySamples = latencyMs * 44100 / 1000  // 110250 samples

// When sending sync packet:
val elapsedMs = System.currentTimeMillis() - streamStartTimeMs
val elapsedSamples = elapsedMs * 44100 / 1000
val currentPlayRtp = startRtpTimestamp + elapsedSamples
val playTimeNtp = System.currentTimeMillis() + latencyMs  // Future time!
```

**Common Mistake:** Sending `rtpTimestamp` with `now` instead of `now + latency`. This causes "Dropping out of date packet" errors because the receiver thinks audio should have played already.

**Send sync packets every ~300ms** to keep the receiver's clock aligned.

---

## Encryption

### Overview

AirPlay 1 uses hybrid encryption:
1. **RSA:** Encrypts the AES key during ANNOUNCE
2. **AES-128-CBC:** Encrypts audio data in each RTP packet

### RSA Key Exchange

Apple's well-known public key (2048-bit RSA):

```
Modulus (Base64):
59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC
5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR
KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB
OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ
Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh
imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew==

Exponent (Base64):
AQAB (= 65537)
```

**RSA Padding:** RSA-OAEP with SHA-1 and MGF1

```kotlin
// CORRECT padding for shairport-sync compatibility
val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
cipher.init(Cipher.ENCRYPT_MODE, publicKey)
val encryptedAesKey = cipher.doFinal(aesKey)
```

**Note:** PKCS1v1.5 padding does NOT work with shairport-sync!

### AES Audio Encryption

Each RTP audio packet is encrypted independently:

1. Generate random 16-byte AES key and 16-byte IV (once per session)
2. For each packet:
   - Use AES-128-CBC with NoPadding
   - Reset cipher to original IV for each packet (no chaining between packets!)
   - Only encrypt complete 16-byte blocks
   - Leave remaining bytes (< 16) unencrypted at end

```kotlin
fun encryptAudio(data: ByteArray, aesKey: ByteArray, aesIv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, 
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(aesIv))
    
    val blockSize = 16
    val numBlocks = data.size / blockSize
    val encryptedSize = numBlocks * blockSize
    
    if (encryptedSize == 0) return data
    
    val encrypted = cipher.doFinal(data.copyOfRange(0, encryptedSize))
    
    // Append unencrypted remainder (if any)
    return if (encryptedSize < data.size) {
        encrypted + data.copyOfRange(encryptedSize, data.size)
    } else {
        encrypted
    }
}
```

### Why Encryption Might Not Be Detected

If your SDP in ANNOUNCE doesn't include `rsaaeskey` and `aesiv` lines, the receiver will expect unencrypted audio. The receiver logs will show "An uncompressed PCM stream has been detected" for L16 without encryption.

**With encryption (SDP includes):**
```
a=rsaaeskey:<base64_encrypted_aes_key>
a=aesiv:<base64_iv>
```

**Without encryption (SDP omits those lines):**
The receiver treats audio as plaintext L16 PCM.

**Important Note:** Even when using L16 format with encryption keys properly sent, shairport-sync may still log "An uncompressed PCM stream has been detected" because:
1. L16 is indeed uncompressed (vs ALAC which is compressed)
2. The "uncompressed" refers to the audio codec, not the encryption status
3. Encryption happens at the transport layer (AES-CBC on the RTP payload), not codec level

To verify encryption is working:
- Check that your ANNOUNCE SDP includes `a=rsaaeskey:` and `a=aesiv:` lines
- If encryption was rejected, shairport-sync would log an RSA decryption error
- Audio playing correctly with encryption keys = encryption is working

---

## Troubleshooting

### Common Issues

#### "Dropping out of date packet"

**Symptom:** Logs show packets being dropped, no audio plays.

**Cause:** Sync timing is wrong. The sync packet is telling the receiver to play audio "now" but the packets haven't arrived yet (or arrived too late).

**Solution:** Ensure sync packet uses future playback time:
```
playTimeNtp = NOW + latency_ms (e.g., 2500ms)
```

#### "Not enough samples to estimate drift"

**Symptom:** Warning during initial connection.

**Cause:** Normal - receiver needs multiple timing samples.

**Solution:** Keep sending timing responses. This resolves automatically.

#### Audio is garbled/static

**Symptom:** Audio plays but sounds wrong.

**Causes:**
1. **Byte order:** L16 requires big-endian. Android provides little-endian.
2. **Encryption mismatch:** Server expects encrypted but receiving unencrypted, or vice versa.
3. **Sample rate mismatch:** Must be exactly 44100Hz.

#### Connection timeout during ANNOUNCE

**Symptom:** ANNOUNCE request times out.

**Causes:**
1. Server is AirPlay 2 only (doesn't support AirPlay 1)
2. Firewall blocking ports
3. SDP format error

#### RSA decryption failed

**Symptom:** shairport-sync logs show RSA decryption error.

**Cause:** Wrong RSA padding scheme.

**Solution:** Use `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`, not PKCS1v1.5.

### Debug Logging

Run shairport-sync with verbose logging:
```bash
shairport-sync --name=MyReceiver -vvvv -o pa 2>&1 | tee /tmp/shairport.log
```

Key log messages:
- `Accepting packet X with timestamp Y. Lead time is Z seconds.` ✓ Good!
- `Dropping out of date packet` ✗ Sync timing wrong
- `An uncompressed PCM stream has been detected` - L16 mode (check if expected)
- `RSA OAEP decryption` - encryption handshake occurring

---

## Quick Reference

### Packet Types

| Type | Byte 1 Value | Direction | Purpose |
|------|--------------|-----------|---------|
| Audio | 0x60 (96) | Client → Server | Audio RTP packets |
| Timing Request | 0x52 | Server → Client | Time sync request |
| Timing Response | 0xD3 | Client → Server | Time sync response |
| Sync | 0xD4 | Client → Server | RTP-to-NTP mapping |

### Port Summary

| Port | Type | Use |
|------|------|-----|
| 5000 | TCP | RTSP control |
| 6001 | UDP | Control (sync packets) |
| 6002 | UDP | Timing |
| 6003 | UDP | Audio RTP |

### Constants

```kotlin
const val SAMPLE_RATE = 44100
const val CHANNELS = 2
const val BITS_PER_SAMPLE = 16
const val FRAMES_PER_PACKET = 352
const val BYTES_PER_PACKET = FRAMES_PER_PACKET * CHANNELS * (BITS_PER_SAMPLE / 8)  // 1408
const val DEFAULT_LATENCY_MS = 2500
const val DEFAULT_LATENCY_SAMPLES = DEFAULT_LATENCY_MS * SAMPLE_RATE / 1000  // 110250
const val NTP_EPOCH_OFFSET = 2208988800L  // Seconds between 1900 and 1970
```

### mDNS Discovery

Service type: `_raop._tcp.local.`
Name format: `<MAC_ADDRESS>@<Friendly Name>`

Example: `D1BC033E2A49@debian`

TXT Record Fields:
| Field | Description |
|-------|-------------|
| `cn` | Codecs: 0=PCM, 1=ALAC, 2=AAC |
| `et` | Encryption types supported |
| `md` | Metadata types |
| `tp` | Transport: UDP or TCP |
| `vn` | Protocol version |
| `vs` | Server version |

---

## References

- [Unofficial AirPlay Protocol Specification](https://nto.github.io/AirPlay.html)
- [shairport-sync source code](https://github.com/mikebrady/shairport-sync)
- [RFC 3550 - RTP](https://datatracker.ietf.org/doc/html/rfc3550)
- [RFC 3551 - RTP Audio/Video Profile](https://datatracker.ietf.org/doc/html/rfc3551)
- [RFC 5905 - NTP](https://datatracker.ietf.org/doc/html/rfc5905)

---

## Changelog

- **January 2026**: Added comprehensive sync packet timing documentation, encryption details, troubleshooting guide
- **December 2025**: Initial protocol overview

