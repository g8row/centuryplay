# centuryplay agent notes

## Project shape

centuryplay is an Android/Kotlin app that captures Android playback audio with `MediaProjection` and streams it to AirPlay/RAOP receivers.

Main areas (v2 engine, see `docs/RESEARCH_AND_ROADMAP.md` for design + verified findings):
- `discovery/`: mDNS discovery and device capability TXT parsing.
- `engine/`: `StreamEngine` (capture thread, `CaptureTimeline`), `StreamSession` (multi-room, reconnect), `SinkFactory` (RAOP vs AirPlay 2 routing).
- `raop/RaopSink.kt` + `RtspConnection.kt`: AirPlay 1 (ALAC/L16, sync, resends, metadata, Digest auth).
- `airplay2/AirPlay2Sink.kt`: AirPlay 2 realtime via transient HAP pairing + NTP (mirror pyatv).
- `audio/AlacEncoder.kt` + `app/src/main/cpp/`: Apple's ALAC encoder via JNI (note the ARM endianness patch in `EndianPortable.c`).
- `service/`: `AudioCaptureService` (FGS, capture source, OS integration), `StreamController` (entry point), `StreamMediaSession` (remote volume), tile.
- `shizuku/`: optional privileged helper (one-tap permission grants, AudioPolicy reroute capture).
- `router/`: MediaRoute2 provider (output switcher, and the routing session that lets volume keys reach our session).
- `docs/`: protocol notes, research and roadmap.
- `tools/collect_macos_airplay_logs.sh`: helper for collecting macOS AirPlay logs while capturing a reproduction.

## Build and test

Use JDK 17:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

Install debug APK to the connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Useful live logs:

```bash
adb logcat | rg 'RaopClient|AudioCaptureService|LogServer|centuryplay'
```

Debug builds (or Settings → debug log server) serve logs at `http://<device-ip>:8080` (`/text` for plain text); use `adb forward tcp:8080 tcp:8080`.

AirPlay 2 test receiver (no prompts, supports transient + PIN 3939 pairing, ALAC): clone
openairplay/airplay2-receiver, `pip install netifaces zeroconf==0.38.3 biplist pycryptodome hexdump srptools hkdf cryptography requests av`,
change its port 7000 → 7010 (macOS ControlCenter owns 7000), stub `pyaudio` with a module whose stream `write()` appends
to a file, and patch two receiver bugs for current PyAV (`codecContext.channels` is read-only → set `layout`;
`bytes(frame.planes[0])` includes alignment padding → slice to `frame.samples * channels * 2`). Then analyse the PCM file.

Testing: never play audible test tones through real speakers at night; use shairport-sync capture instances (`-o stdout > file`) and quiet tones, and stop the player after tests (a stopped/failed reroute sends audio back to the phone speaker).

## Current receiver support

Known working path:
- AirPlay 1 / RAOP receivers that support RSA key exchange (`et=1`), such as shairport-sync.
- L16/44100/2 PCM over RTP/UDP with RAOP timing and sync.

Known unsupported path:
- Receivers that advertise FairPlay SAPv2 only (`et=5` without `et=1`), such as the tested Samsung AirScreen setup.
- The app should show a FairPlay-required message and avoid starting MediaProjection for these devices.

## FairPlay/AirScreen findings

Samsung AirScreen advertised:

```text
_airplay._tcp  port 57000  model=AppleTV3,1 srcvers=220.68 vv=2 pk=...
_raop._tcp     port 5000   cn=0,1,2,3 et=0,3,5 am=AppleTV3,1 pk=...
```

macOS Music did not use the AirPlay 2/HAP audio path for this test. It used RAOP TCP 5000 with FairPlay SAPv2 before `ANNOUNCE`:

```text
connection 1: POST /fp-setup phase 1, POST /fp-setup phase 2, OPTIONS, close
connection 2: POST /fp-setup phase 1, POST /fp-setup phase 2, ANNOUNCE, SETUP, RECORD
```

The macOS phase 1 FPLY body was:

```text
46 50 4c 59 02 01 01 00 00 00 00 04 02 00 03 bb
```

AirScreen returns a 142-byte phase 1 response and a 32-byte phase 2 response only when phase 2 is cryptographically valid. Dummy/random 164-byte phase 2 bodies get a 12-byte FPLY error:

```text
1e 1e 1e 1e 02 01 04 9c 00 00 00 00
```

Do not assume a stub `/fp-setup` is enough. Real streaming to this path requires sender-side FairPlay SAPv2 crypto capable of producing the phase 2 message and `a=fpaeskey`.

## Repository hygiene

Do not commit local capture artifacts unless explicitly requested:
- `airplay-mac-logs-*`
- `*.pcap`

When editing, keep protocol experiments isolated and preserve unrelated user changes in the working tree.
