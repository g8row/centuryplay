# airplay 1 (raop) protocol documentation

> **status**: comprehensive reference based on reverse engineering and implementation
> **last updated**: january 2026

this document provides a complete reference for the airplay 1 protocol (also known as raop - remote audio output protocol), based on reverse engineering and implementation experience with shairport-sync receivers.

## table of contents

1. [overview](#overview)
2. [protocol architecture](#protocol-architecture)
3. [rtsp control protocol](#rtsp-control-protocol)
4. [session establishment](#session-establishment)
5. [audio streaming](#audio-streaming)
6. [time synchronization](#time-synchronization)
7. [encryption](#encryption)
8. [troubleshooting](#troubleshooting)
9. [quick reference](#quick-reference)

---

## overview

airplay 1 is apple's proprietary audio streaming protocol, introduced circa 2004 as "airtunes" and later renamed to airplay. it enables wireless audio streaming from a sender (client) to a receiver (speaker/server).

### key characteristics

| property | value |
|----------|-------|
| control protocol | rtsp (real-time streaming protocol) |
| control port | tcp 5000 (default) |
| audio transport | rtp over udp |
| audio format | l16/44100/2 (16-bit pcm, 44.1khz, stereo) or encrypted alac |
| encryption | aes-128-cbc with rsa key exchange |
| discovery | mdns/bonjour (`_raop._tcp`) |

### protocol flow summary

```
client                                    server (receiver)
   |                                           |
   |  ---- options (capabilities) -----------> |
   |  <-------- 200 ok ----------------------- |
   |                                           |
   |  ---- announce (sdp with format) -------> |
   |  <-------- 200 ok ----------------------- |
   |                                           |
   |  ---- setup (port negotiation) ---------> |
   |  <-------- 200 ok (ports) --------------- |
   |                                           |
   |  ---- record (start streaming) ---------> |
   |  <-------- 200 ok ----------------------- |
   |                                           |
   |  ==== udp: timing packets <==============>| (bidirectional)
   |  ==== udp: sync packets ================> | (client to server)
   |  ==== udp: audio rtp packets ==========> | (client to server)
   |                                           |
   |  ---- set_parameter (volume, etc) ------> |
   |  <-------- 200 ok ----------------------- |
   |                                           |
   |  ---- teardown (end session) -----------> |
   |  <-------- 200 ok ----------------------- |
```

---

## protocol architecture

### network ports

airplay 1 uses multiple ports for different purposes:

| port type | protocol | direction | purpose |
|-----------|----------|-----------|---------|
| rtsp control | tcp | bidirectional | session control, commands |
| audio | udp | client → server | rtp audio packets |
| control | udp | client → server | sync/control packets |
| timing | udp | bidirectional | ntp-like time synchronization |

**default port ranges:**
- rtsp: tcp 5000
- udp base: 6001 (control), 6002 (timing), 6003 (audio)

### client identifiers

each airplay client must provide several identifiers:

```
client-instance: 9910f4fad33b72f1  (16 hex chars, random)
dacp-id: 9910f4fad33b72f1          (same as client-instance)
active-remote: 1478837813          (random 32-bit integer as string)
```

**purpose:**
- `client-instance`: unique identifier for this client session
- `dacp-id`: digital audio control protocol id for remote control
- `active-remote`: used for bidirectional remote control communication

---

## rtsp control protocol

all rtsp messages follow this format:

### request format
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

### response format
```
RTSP/1.0 <status_code> <reason>
CSeq: <matching_sequence_number>
Server: AirTunes/105.1
[Additional Headers]

[Body if Content-Length > 0]
```

### common status codes
| code | meaning |
|------|---------|
| 200 | ok - success |
| 400 | bad request |
| 453 | not enough bandwidth |
| 501 | not implemented |

---

## session establishment

### step 1: options (optional but recommended)

tests server connectivity and retrieves capabilities.

**request:**
```
OPTIONS * RTSP/1.0
CSeq: 1
User-Agent: iTunes/10.6 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Apple-Challenge: lY5pmgHcGK2IJ8RnKnUb9w==
```

**response:**
```
RTSP/1.0 200 OK
CSeq: 1
Server: AirTunes/105.1
Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER
Apple-Response: <base64_challenge_response>
```

**apple-challenge/response:**
- challenge: 16 random bytes, base64 encoded
- response: rsa signature proving server has the private key
- used to verify authentic airplay receivers

### step 2: announce

describes the audio format and provides encryption keys.

**request:**
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

**sdp fields explained:**

| field | meaning |
|-------|---------|
| `v=0` | sdp version |
| `o=itunes <session_id> 0 in ip4 <client_ip>` | origin (sender info) |
| `s=itunes` | session name |
| `c=in ip4 <server_ip>` | connection (receiver ip) |
| `t=0 0` | time (always 0 0 for streaming) |
| `m=audio 0 rtp/avp 96` | media line: audio, port 0, rtp, payload 96 |
| `a=rtpmap:96 l16/44100/2` | payload 96 is l16, 44100hz, 2 channels |
| `a=rsaaeskey:<key>` | rsa-encrypted aes key (if encrypted) |
| `a=aesiv:<iv>` | aes initialization vector (if encrypted) |

**audio format options:**
- `l16/44100/2` - uncompressed 16-bit pcm (simpler, higher bandwidth)
- `applelossless` - alac compressed (more complex, lower bandwidth)

### step 3: setup

negotiates udp ports for audio, control, and timing.

**request:**
```
SETUP rtsp://192.168.1.220/7981095476330793800 RTSP/1.0
CSeq: 3
User-Agent: iTunes/12.3 (Windows; N)
Client-Instance: 9910F4FAD33B72F1
DACP-ID: 9910F4FAD33B72F1
Active-Remote: 1478837813
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=57099;timing_port=58571
```

**response:**
```
RTSP/1.0 200 OK
CSeq: 3
Server: AirTunes/105.1
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002;server_port=6003
Session: 1
```

**transport header parameters:**

| parameter | meaning |
|-----------|---------|
| `rtp/avp/udp` | rtp audio/video profile over udp |
| `unicast` | point-to-point (not multicast) |
| `interleaved=0-1` | channel interleaving |
| `mode=record` | client is sending (not receiving) |
| `control_port` | udp port for sync/control packets |
| `timing_port` | udp port for time synchronization |
| `server_port` | server's audio receive port |

### step 4: record

starts the streaming session.

**request:**
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

**rtp-info header:**
- `seq`: starting rtp sequence number (16-bit, random initial value)
- `rtptime`: starting rtp timestamp (32-bit, random initial value)

**response:**
```
RTSP/1.0 200 OK
CSeq: 4
Server: AirTunes/105.1
Audio-Latency: 11025
```

**audio-latency:** server's internal latency in samples (at 44100hz)
- 11025 samples = 250ms

### step 5: set_parameter (optional)

sets volume and other parameters during playback.

**volume request:**
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

**volume scale:**
- `-144.0` = mute
- `-30.0` = very quiet
- `0.0` = maximum volume

### step 6: teardown

ends the session and releases resources.

**request:**
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

## audio streaming

### rtp packet format

audio is sent as rtp packets over udp to the server's audio port.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|v=2|p|x|  cc   |m|     pt      |       sequence number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                              ssrc                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         audio payload                         |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**header fields (12 bytes):**

| field | bits | value | description |
|-------|------|-------|-------------|
| v | 2 | 2 | rtp version |
| p | 1 | 0 | padding flag |
| x | 1 | 0 | extension flag |
| cc | 4 | 0 | csrc count |
| m | 1 | 0 | marker bit |
| pt | 7 | 96 | payload type (dynamic) |
| sequence | 16 | varies | packet sequence (wraps at 65535) |
| timestamp | 32 | varies | rtp timestamp (samples) |
| ssrc | 32 | random | synchronization source id |

### l16 audio payload

for uncompressed pcm (l16/44100/2):

- **byte order:** big-endian (network byte order) - **critical!**
- **sample size:** 16 bits per sample
- **channels:** 2 (stereo, interleaved: l, r, l, r, ...)
- **samples per packet:** 352 frames (352 × 4 = 1408 bytes)
- **sample rate:** 44100 hz

**important:** android's audiorecord provides little-endian samples. you must convert to big-endian before sending!

```kotlin
// swap bytes for 16-bit samples (little-endian → big-endian)
fun swapEndianness(data: ByteArray): ByteArray {
    val result = ByteArray(data.size)
    for (i in 0 until data.size step 2) {
        result[i] = data[i + 1]      // high byte first
        result[i + 1] = data[i]      // low byte second
    }
    return result
}
```

### timestamp progression

the rtp timestamp increments by the number of audio frames per packet:

```
initial timestamp: t₀ (random 32-bit value)
after packet 1: t₀ + 352
after packet 2: t₀ + 704
after packet n: t₀ + (n × 352)
```

timestamp wraps at 2³² (4,294,967,296).

---

## time synchronization

time sync is **critical** for audio playback. without it, the receiver doesn't know when to play audio frames.

### ntp timestamps

airplay uses ntp-style timestamps (64-bit):
- **seconds:** 32-bit, seconds since january 1, 1900
- **fraction:** 32-bit, fractional seconds (2³² = 1 second)

```kotlin
// convert unix milliseconds to ntp timestamp
val ntpSec = (unixMillis / 1000) + 2208988800L  // unix epoch → ntp epoch
val ntpFrac = ((unixMillis % 1000) * 4294967296.0 / 1000.0).toLong()
```

**epoch difference:** ntp epoch is january 1, 1900. unix epoch is january 1, 1970.
- difference: 2,208,988,800 seconds (70 years)

### timing protocol (port 6002)

the receiver sends timing requests, client must respond.

**timing request (32 bytes):**
```
Bytes 0-7:   Header
Bytes 8-15:  Origin timestamp (T₁) - filled on reply
Bytes 16-23: Receive timestamp (T₂) - filled on reply  
Bytes 24-31: Transmit timestamp (T₃) - when server sent request
```

**timing response (32 bytes):**
```
Byte 0:      0x80 (RTP version 2)
Byte 1:      0xD3 (0x53 | 0x80 = timing reply)
Bytes 2-7:   Copy from request
Bytes 8-15:  T_orig = copy T_xmit from request (bytes 24-31)
Bytes 16-23: T_recv = when we received the request
Bytes 24-31: T_xmit = when we're sending the reply
```

### sync/control protocol (port 6001)

the client must send sync packets to tell the receiver the rtp-to-ntp time mapping.

**sync packet (20 bytes):**
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|v=2|p|x|  cc   |m|   pt=0xd4   |       sequence number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    current rtp timestamp                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                   ntp timestamp (64-bit)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     next rtp timestamp                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**fields:**
| offset | size | field | description |
|--------|------|-------|-------------|
| 0 | 1 | header | 0x90 (first packet) or 0x80 (subsequent) |
| 1 | 1 | type | 0xd4 (payload type 84 = sync) |
| 2 | 2 | sequence | sync packet sequence number |
| 4 | 4 | rtp time | rtp timestamp to play at ntp time |
| 8 | 8 | ntp time | when the rtp timestamp should play |
| 16 | 4 | next rtp | next sync point (rtp + latency) |

### sync timing logic (critical!)

the sync packet answers: "when should the receiver play rtp timestamp x?"

**latency concept:**
- the receiver needs time to buffer audio before playback
- standard latency: ~2.5 seconds (110,250 samples at 44100hz)
- sync tells receiver: "play rtp timestamp x at time (now + latency)"

```kotlin
// correct sync timing
val latencyMs = 2500L  // 2.5 seconds
val latencySamples = latencyMs * 44100 / 1000  // 110250 samples

// when sending sync packet:
val elapsedMs = System.currentTimeMillis() - streamStartTimeMs
val elapsedSamples = elapsedMs * 44100 / 1000
val currentPlayRtp = startRtpTimestamp + elapsedSamples
val playTimeNtp = System.currentTimeMillis() + latencyMs  // future time!
```

**common mistake:** sending `rtptimestamp` with `now` instead of `now + latency`. this causes "dropping out of date packet" errors.

**send sync packets every ~300ms** to keep the receiver's clock aligned.

---

## encryption

### overview

airplay 1 uses hybrid encryption:
1. **rsa:** encrypts the aes key during announce
2. **aes-128-cbc:** encrypts audio data in each rtp packet

### rsa key exchange

apple's well-known public key (2048-bit rsa):

```
modulus (base64):
59de8qlieitsh1wgjrcfrkj6euwqi+bglox1hl3u3ghc/j0qg90u3sg/1cutwc
5voyvfdmfi6osfxi5elabwjmT2dkhzbjkak9ok+8t9ucrqmd6dzhj2ycclldr
kskv6kdqnw4uwpdpomxic/amj3z/lurx1g7wshcawkf1zns1elvqr+boejxub
oitnz/bdzphrtozz0dew0uowxf/+sg+nck3eqjvxqcaj/vehkivd2m+5ql71yj
q+87x6ov3eayvt3zwzyd6z5vytcrtij2vz9zmni/uahqq9jdsbwluepviymnh
imnvvyfzecxg/idtq+x4irdixnv5heew==

exponent (base64):
aqab (= 65537)
```

**rsa padding:** rsa-oaep with sha-1 and mgf1

```kotlin
// correct padding for shairport-sync compatibility
val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
cipher.init(Cipher.ENCRYPT_MODE, publicKey)
val encryptedAesKey = cipher.doFinal(aesKey)
```

**note:** pkcs1v1.5 padding does not work with shairport-sync!

### aes audio encryption

each rtp audio packet is encrypted independently:

1. generate random 16-byte aes key and 16-byte iv (once per session)
2. for each packet:
   - use aes-128-cbc with nopadding
   - reset cipher to original iv for each packet (no chaining!)
   - only encrypt complete 16-byte blocks
   - leave remaining bytes (< 16) unencrypted at end

```kotlin
fun encryptAudio(data: ByteArray, aesKey: ByteArray, aesIv: ByteArray): ByteArray {
    // ...
}
```

### why encryption might not be detected

if your sdp in announce doesn't include `rsaaeskey` and `aesiv` lines, the receiver will expect unencrypted audio.

**with encryption (sdp includes):**
```
a=rsaaeskey:<base64_encrypted_aes_key>
a=aesiv:<base64_iv>
```

**without encryption (sdp omits those lines):**
the receiver treats audio as plaintext l16 pcm.

to verify encryption is working:
- check that your announce sdp includes `a=rsaaeskey:` and `a=aesiv:` lines
- audio playing correctly with encryption keys = encryption is working

---

## troubleshooting

### common issues

#### "dropping out of date packet"

**symptom:** logs show packets being dropped, no audio plays.
**cause:** sync timing is wrong.
**solution:** ensure sync packet uses future playback time:
```
playTimeNtp = NOW + latency_ms (e.g., 2500ms)
```

#### "not enough samples to estimate drift"

**symptom:** warning during initial connection.
**cause:** normal - receiver needs multiple timing samples.
**solution:** keep sending timing responses.

#### audio is garbled/static

**symptom:** audio plays but sounds wrong.
**causes:**
1. **byte order:** l16 requires big-endian.
2. **encryption mismatch:** server expects encrypted but receiving unencrypted.
3. **sample rate mismatch:** must be exactly 44100hz.

#### connection timeout during announce

**symptom:** announce request times out.
**causes:**
1. server is airplay 2 only
2. firewall blocking ports
3. sdp format error

#### rsa decryption failed

**symptom:** shairport-sync logs show rsa decryption error.
**cause:** wrong rsa padding scheme.
**solution:** use `rsa/ecb/oaepwithsha-1andmgf1padding`.

### debug logging

run shairport-sync with verbose logging:
```bash
shairport-sync --name=MyReceiver -vvvv -o pa 2>&1 | tee /tmp/shairport.log
```

---

## quick reference

### packet types

| type | byte 1 value | direction | purpose |
|------|--------------|-----------|---------|
| audio | 0x60 (96) | client → server | audio rtp packets |
| timing request | 0x52 | server → client | time sync request |
| timing response | 0xd3 | client → server | time sync response |
| sync | 0xd4 | client → server | rtp-to-ntp mapping |

### port summary

| port | type | use |
|------|------|-----|
| 5000 | tcp | rtsp control |
| 6001 | udp | control (sync packets) |
| 6002 | udp | timing |
| 6003 | udp | audio rtp |

### constants

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

### mdns discovery

service type: `_raop._tcp.local.`
name format: `<mac_address>@<friendly name>`
example: `d1bc033e2a49@debian`

---

## references

- [unofficial airplay protocol specification](https://nto.github.io/AirPlay.html)
- [shairport-sync source code](https://github.com/mikebrady/shairport-sync)
- [rfc 3550 - rtp](https://datatracker.ietf.org/doc/html/rfc3550)
- [rfc 3551 - rtp audio/video profile](https://datatracker.ietf.org/doc/html/rfc3551)
- [rfc 5905 - ntp](https://datatracker.ietf.org/doc/html/rfc5905)

---

## changelog

- **january 2026**: added comprehensive sync packet timing documentation, encryption details, troubleshooting guide
- **december 2025**: initial protocol overview
