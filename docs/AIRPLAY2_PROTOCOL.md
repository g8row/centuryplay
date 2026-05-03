# airplay 2 protocol & implementation guide

> **status**: research & implementation draft
> **last updated**: may 2026

this document covers the airplay 2 protocol, focusing on the technical requirements for an android implementation, including the shizuku workaround for ptp synchronization.

## table of contents

1. [overview](#overview)
2. [protocol differences (ap1 vs ap2)](#protocol-differences)
3. [pairing & security (hap)](#pairing--security)
4. [session establishment (rtsp)](#session-establishment)
5. [audio formats & codecs](#audio-formats--codecs)
6. [time synchronization (ptp)](#time-synchronization)
7. [shizuku-based implementation plan](#shizuku-implementation)
8. [technical hurdles](#technical-hurdles)

---

## overview

airplay 2 (ap2) is a major evolution of apple's wireless streaming protocol. it introduces multi-room audio, enhanced buffering, and modern security (hap). unlike airplay 1 (raop), which is relatively simple and unencrypted by default, airplay 2 is encrypted from the start and requires tight clock synchronization between all devices in a group.

Important capture note: a receiver advertising `_airplay._tcp` does not mean macOS Music will necessarily use the AirPlay 2 audio path. Samsung AirScreen advertises `_airplay._tcp` on a dynamic port, but the macOS Music capture used RAOP TCP 5000 with FairPlay SAPv2 (`POST /fp-setup`) and then AppleLossless in the classic RAOP session.

---

## protocol differences

| feature | airplay 1 (raop) | airplay 2 |
|---------|------------------|-----------|
| **control** | standard rtsp/sdp | rtsp with binary plist (bplist) |
| **security** | aes-128-cbc + rsa | chacha20-poly1305 + curve25519 |
| **pairing** | none (or apple challenge) | hap (homekit accessory protocol) |
| **timing** | ntp-style (port 6002) | ptp (ieee 1588, ports 319/320) |
| **buffering** | static (~2s) | dynamic / buffered audio |
| **discovery** | `_raop._tcp` | `_airplay._tcp` + `_raop._tcp` |

---

## pairing & security (hap)

airplay 2 requires mandatory pairing using the homekit accessory protocol (hap). this establishes a trusted relationship between the sender and receiver.

### 1. pair-setup (one-time)
uses **srp-6a** (secure remote password) to exchange a shared secret without ever sending the password over the wire.
- **client:** sends `pair-setup` start request.
- **server:** responds with salt and public key.
- **exchange:** both parties compute a session key.

### 2. pair-verify (every session)
uses **curve25519** and **ed25519** for fast, secure authentication of an existing pairing.
- establishes a transient session key for the rtsp control channel.
- control channel is encrypted using **chacha20-poly1305**.

---

## session establishment (rtsp)

airplay 2 replaces standard sdp (session description protocol) with **binary plists (bplist)** inside the rtsp body.

### 1. discovery & info
the client first calls `GET /info` to retrieve the server's capabilities.
```
X-Apple-ProtocolVersion: 1
Content-Type: application/x-apple-binary-plist
```
the response contains a bplist with `features`, `deviceid`, `model`, etc.

### 2. setup
the `SETUP` request defines the stream parameters.
```
{
  "streams": [
    {
      "type": 96,
      "ct": 1,           // 1=PCM, 2=ALAC, 4=AAC
      "spf": 352,        // Samples per frame
      "latencyMin": 0,
      "latencyMax": 0,
      "shk": <shared_key> // Shared encryption key
    }
  ]
}
```

---

## audio formats & codecs

airplay 2 supports higher quality and more efficient codecs than airplay 1.

| codec | resolution | usage |
|-------|------------|-------|
| **alac** | 16/44.1 or 24/48 | standard lossless |
| **aac** | 256kbps | lossy / bandwidth efficient |
| **pcm (l16)** | 16/44.1 | raw audio before encapsulation |

**note:** apple music on android often defaults to aac (256kbps) for third-party speakers, even if the source is lossless.

---

## time synchronization (ptp)

the biggest hurdle for android implementations is **ptp (precision time protocol)**.

- **purpose:** ensures all speakers in a group play the exact same sample at the exact same microsecond.
- **ports:** requires binding to **udp ports 319 and 320**.
- **problem:** these are privileged ports (< 1024) on android/linux. standard apps cannot access them without root.

---

## shizuku-based implementation plan

to enable airplay 2 multi-room sync without root, we use **shizuku** to run a privileged native bridge.

### 1. the ptp bridge (`ptp-bridge`)
a small native binary compiled via ndk that:
- binds to udp 319/320 as the `shell` user (uid 2000).
- implements a minimal ptp client/slave logic.
- shares the calculated clock offset with the main app.

### 2. data exchange
- **shared memory:** use `android.os.SharedMemory` to pass timing data from the shizuku service to the app process with zero latency.
- **local socket:** use a unix domain socket in `/data/local/tmp/` for command/control of the bridge.

### 3. shizuku workflow
1. **app** checks for shizuku availability.
2. **app** requests shizuku permission.
3. **app** uses `Shizuku.newProcess` to launch `ptp-bridge`.
4. **bridge** binds to ports and starts syncing.
5. **app** reads timing data from shared memory to adjust its rtp timestamps.

---

## technical hurdles

1. **shizuku dependency:** requires the user to have shizuku installed and configured (via adb).
2. **jitter:** without hardware timestamping (which android doesn't expose to apps), ptp accuracy is limited to software-level precision (~1-5ms), which is enough for stable playback but might struggle with "perfect" multi-room phase alignment.
3. **battery:** running a high-frequency ptp clock and real-time audio capture is power-intensive.
4. **hybrid receivers:** devices may advertise AirPlay 2 discovery while still accepting Music audio over RAOP plus FairPlay SAPv2. For centuryplay, treat `_raop._tcp` TXT `et=5` without `et=1` as a FairPlay sender problem, not as proof that the AirPlay 2/HAP path was selected.

---

## references

- [official hap specification](https://developer.apple.com/homekit/specification/)
- [nqptp source code](https://github.com/mikebrady/nqptp)
- [ap2-sender (python)](https://github.com/openairplay/ap2-sender)
- [shizuku documentation](https://shizuku.rikka.app/)
