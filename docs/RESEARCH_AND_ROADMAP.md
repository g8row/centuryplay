# centuryplay — research notes, verified findings and roadmap

Written during the September 2026 overhaul (v2.0). Everything marked **verified** was tested
on real hardware: Xiaomi Mi 11 (Android 14, MIUI, not rooted, Shizuku 13.5 via adb),
a Raspberry Pi 3 running shairport-sync 4.3.7 (AirPlay 1 build "Century", plus an AirPlay 2
build "Century AP2" with nqptp), a native shairport-sync on macOS writing to a file, and the
Mac's own AirPlay receiver.

---

## 1. Architecture (v2)

```
 PcmSource ──► StreamEngine (capture thread, URGENT_AUDIO)
 (Shizuku pipe │   packetises 352 frames, tracks capture clock (CaptureTimeline),
  or MediaProj)│   fills stalls with silence, meters level, detects silence
               ▼
        AudioPacket (PCM LE; ALAC/BE-PCM encoded once, shared)
               │ fan-out (multi-room)
     ┌─────────┼───────────┐
  RaopSink  RaopSink   AirPlay2Sink        ← one per speaker, same timeline + NTP clock
     │
  StreamSession: add/remove speakers live, per-speaker + group volume, reconnect w/ backoff,
                 AirPlay 2 → RAOP fallback, password/accept states
     │
  AudioCaptureService (FGS): source selection, notification (MediaStyle), wake + Wi-Fi
     locks, phone-silencing, remote-volume MediaSession, metadata forwarding, auto-stop
     │
  StreamController ◄── MainActivity / TileDeviceActivity / QS tile / MediaRoute2 provider
```

Files: `engine/*`, `raop/RaopSink.kt`, `raop/RtspConnection.kt`, `airplay2/AirPlay2Sink.kt`,
`audio/AlacEncoder.kt` + `cpp/` (Apple ALAC via JNI), `shizuku/*`, `service/*`, `router/*`.

## 2. Verified protocol findings

### RAOP timing (sync packets)
shairport-sync `rtp.c` interprets a 0xD4 sync packet as: *frame `rtp[16] − latency` plays at
`ntp[8]`*, where `latency = rtp[16] − rtp[4] (+11025 when flags == 0x0007)`. For live capture
the right mapping is therefore: `rtp[16]` = frame being captured now, `rtp[4]` =
`rtp[16] − (latency − 11025)`, `ntp` = now. Every frame then plays exactly `latency` after it
was captured, on every receiver → multi-room sync for free.

The capture clock (audio HAL) drifts from the system clock. `CaptureTimeline` follows the lower
envelope of `now − frames/rate` (instant drop, slow rise) so jitter is ignored but drift is
tracked; sync packets use it instead of wall-clock-since-start. **Verified**: zero missing/late
packets, source drift 0.00 ppm in shairport statistics over multi-minute runs.

### ALAC
* Apple's reference encoder (Apache 2.0) is compiled via NDK. **Bug found**: Apple's
  `EndianPortable.c` only treats x86 as little-endian, so on ARM every byte swap is skipped and
  the bitstream is garbage (decodes to noise). Fixed with `__BYTE_ORDER__` detection.
* **Verified** bit-exact with shairport's built-in (Hammerton) decoder in both normal and fast
  mode (offline harness), and end-to-end on phone → receivers (clean 1 kHz L / 1.5 kHz R).
* Uncompressed "escape" ALAC fallback in Kotlin if the native lib can't load.

### AirPlay 2
* **AirPlay 2 + ALAC audio verified end-to-end** (Sept 26) against openairplay `airplay2-receiver`
  on the LAN (port 7010, pyaudio stubbed to write PCM): transient pairing with no prompt, HAP
  event channel, ALAC stream setup (ct=2, 0x40000), clean 1 kHz/1.5 kHz at full scale, playout
  offset ≈ +8 ms, automatic reconnect when the receiver restarted. Two bugs had to be fixed in
  the *test receiver* (newer PyAV: `codecContext.channels` read-only; `bytes(frame.planes[0])`
  includes alignment padding → 32 zero samples per 352-frame packet).
* **Mac receiver, verified with persistent pairing** (Sept 26): after `/pair-pin-start` +
  Accept, macOS shows a 4-digit "AirPlay Code" (like an Apple TV); Pair-Setup + Pair-Verify
  succeed and later sessions pair-verify with the stored credentials. In "anyone on the same
  network" mode macOS still asks to *Accept every session* — for a paired sender it holds SETUP
  until answered, so SETUP now waits up to 60 s and reports "accept on the speaker" otherwise.
* **Persistent HAP pairing verified** (Sept 26, airplay2-receiver, PIN 3939): Pair-Setup M1–M6
  (SRP-6a 3072 + Ed25519 long-term keys, ChaCha20 "PS-Msg05/06"), Pair-Verify (X25519,
  "PV-Msg02/03"), control keys from the X25519 secret, clean ALAC audio, and later reconnects
  use the stored pairing without any prompt (`airplay2/HapPairing.kt`, mirrors pyatv
  `auth/hap_srp.py` + `protocols/airplay/auth/hap.py`). Flow: stored credentials → verify;
  else transient; transient rejected or "pair with code" → setup with the code shown on the TV /
  the receiver's AirPlay password (asked inline while the connection stays open, since an
  Apple TV's code belongs to that connection).
* DACP remote control **verified** from the LAN: `iTunes_Ctrl_<id>._dacp._tcp` advertised,
  `/ctrl-int/1/<cmd>` with the right `Active-Remote` → 204 and dispatched; wrong token → 403.
* Transient HAP pairing + NTP realtime audio (pyatv model) **verified** against a Mac receiver
  (macOS 15 "Anyone on the same network"): pairing, SETUP ×2, RECORD. The Mac shows an
  Accept/Decline prompt per sender; pair-setup blocks until answered (we now wait 60 s and
  report "accept on the speaker").
* **shairport-sync AirPlay 2 mode cannot do NTP** ("Shairport Sync can not handle NTP
  streams", rtsp.c) — PTP only. We detect `model=Shairport Sync` and use its RAOP endpoint.
  Any AirPlay 2 handshake failure also falls back to RAOP when available.
* The event channel must be HAP-encrypted (out key "Events-Read-Encryption-Key", in key
  "Events-Write-Encryption-Key") and must answer each request with 200 OK — implemented per
  pyatv `channels.py`.
* Old code omitted `Content-Length` on bodyless POSTs and read HAP plaintext with O(n²) copies;
  both fixed.

### Metadata
DMAP `mlit{minm,asar,asal}`, `image/jpeg` artwork and `progress:` **verified** arriving at
shairport-sync's metadata pipe (title + 14.5 KB JPEG + progress), deduplicated per track.

## 3. OS integration research

### 3.1 Skipping the MediaProjection consent dialog
`appops set <pkg> PROJECT_MEDIA allow` makes `createScreenCaptureIntent()` return RESULT_OK
immediately with no UI (**verified** Android 14/MIUI). Grantable once via adb or Shizuku.

### 3.2 Shizuku (shell uid 2000) — what it unlocks
Shell holds (verified via `dumpsys package com.android.shell`): `MODIFY_AUDIO_ROUTING`,
`CAPTURE_AUDIO_OUTPUT`, `CAPTURE_MEDIA_OUTPUT`, `MANAGE_AUDIO_POLICY`, `MEDIA_CONTENT_CONTROL`,
`MEDIA_ROUTING_CONTROL`, `MODIFY_AUDIO_SETTINGS_PRIVILEGED`, …

* **One-tap setup** (`ShizukuManager.setupPermissions`): PROJECT_MEDIA appop, RECORD_AUDIO,
  POST_NOTIFICATIONS, notification-listener access (`cmd notification allow_listener`),
  `cmd deviceidle whitelist +pkg`, RUN_ANY_IN_BACKGROUND. **Verified** all six.
* **Rerouting capture** (`PrivilegedService`, a Shizuku UserService): registers an AudioPolicy
  loopback mix (`ROUTE_FLAG_LOOP_BACK`, usages MEDIA/GAME/UNKNOWN) exactly like scrcpy's
  `--audio-source=playback`. Media audio goes *only* to our capture → the phone speaker is
  bypassed, no MediaProjection, no status-bar cast chip. **Verified**: VLC's track moved to
  the policy's Remote Submix port, clean tone at the receiver.
  * Gotcha: AudioPolicy checks `MODIFY_AUDIO_ROUTING` against the *binder caller*; the
    service must `Binder.clearCallingIdentity()` around `createAudioRecordSink`.
  * Gotcha: if setup fails after `registerAudioPolicy`, unregister it, or media stays routed to
    a mix nobody reads.
  * Needs Android 13+ (`setTargetMixRole`); falls back to MediaProjection otherwise.
* Not used (yet): `WRITE_SECURE_SETTINGS` grant would let the app enable an accessibility
  service or change settings itself.

### 3.3 MediaProjection capture is pre-volume
Captured audio is unaffected by the phone's media volume (**verified**: identical level at
index 10, 1 and 0). So "keep phone silent" can set media volume to 0 while streaming.

### 3.4 Hardware volume keys → AirPlay speakers
AOSP 14 `MediaSessionService.dispatchAdjustVolumeLocked` sends volume keys to the default
volume session unless the foreground app pinned `setVolumeControlStream` and that stream is
active. A *remote* session (our `StreamMediaSession` with a `VolumeProvider`) only qualifies if
`MediaSessionRecord.canHandleVolumeKey()` is true, which (when
`config_volumeAdjustmentForRemoteGroupSessions` is false, as on MIUI) requires the package to
own a **non-system MediaRouter2 routing session** (b/228021646).
Solution: while streaming, our `MediaRoute2ProviderService` publishes a routing session owned by
our own package via `notifySessionCreated(REQUEST_ID_NONE, …)`. **Verified**: volume keys from
the home screen now move our remote session (40 → 67 %) and the receiver level; the phone's
index is untouched; the system volume panel shows the stream slider.
Fallback when keys still hit the phone stream: relative steps applied to speakers and the phone
index reset (silenced mode only).

### 3.5 System Output Switcher (media card output chip) — **verified working for any app**
How SystemUI builds the list (AOSP 14, SettingsLib `InfoMediaManager.getAvailableRoutes`): for
the playing app it takes the app's *latest* routing session (`MediaRouter2Manager
.getRoutingSessions(pkg)` = system session + every provider session whose
`clientPackageName == pkg`) and shows its selected + selectable routes plus
`getTransferableRoutes`. In `MediaRouter2Manager.getFilteredRoutes`, routes named in the
session's transferable/selected lists **bypass** the app's discovery-preference feature filter;
everything else must match features the app registered — which ordinary players (VLC, Spotify
for AirPlay purposes) never do. That's why third-party routes normally never show up.

Our provider therefore publishes a routing session **on behalf of the playing app**
(`notifySessionCreated(REQUEST_ID_NONE, RoutingSessionInfo(clientPackageName = thatApp))`,
the app found via MediaSessionManager):
* idle: selected = our "This phone" stand-in route, transferable = all AirPlay speakers;
* streaming: selected = playing speakers, selectable = the others ("+" = multi-room),
  transferable = "This phone" (tap → stop).

**Verified** on Android 14 with VLC: the switcher lists the AirPlay speakers under
"Speakers & Displays"; tapping one starts streaming silently (Shizuku) and it becomes the
active output with a volume slider and "+" for other speakers; tapping "This phone" stops and
pauses the player. Caveat handled: while our session stands in, system routes (Bluetooth /
wired headphones) would be hidden, so the idle session is only offered when no external output
is connected (AudioDeviceCallback). Toggle: Settings → "speakers in the system output switcher".

GitHub issue #1 (MediaRouteProvider instead of capture): routes alone can't carry audio — apps
that use MediaRouter expect Cast/remote-playback semantics — so capture stays the transport and
the routes are the control surface.

### 3.6 Quick Settings tile
Tap = reconnect last speakers silently (Shizuku or held projection) or open the picker; tap while
streaming = stop; long-press (`QS_TILE_PREFERENCES`) = picker with per-speaker volume. Settings
offers `StatusBarManager.requestAddTileService` (Android 13+).

### 3.7 Discovery: NsdManager needs a MulticastLock
Moving from JmDNS to the system mDNS stack (NsdManager) fixed the Android 17 EPERM crash, but
receivers that answer queries by *multicast* (avahi → shairport-sync, many AVRs) then went
missing intermittently: Wi-Fi drivers filter multicast in power save unless an app holds a
`WifiManager.MulticastLock`, and the system stack doesn't take one for the querying app
(`dumpsys servicediscovery` shows queries going out, but no answers). Holding the lock while
discovery runs made all receivers appear within ~3 s. Discovery also restarts itself when stale
(foreground after >5 min, network change, nothing found after 6 s).

## 4. Performance audit (done)
* Old hot path: coroutine `withContext(IO)` per 1408-byte chunk, `ByteArrayOutputStream`
  copy per read, `InetAddress.getByName` per packet, new `Cipher` per packet → replaced by a
  single capture thread, one allocation per packet, cached address, cached encodings.
* Measured while streaming ALAC to two receivers: **~8 % of one core** app process, ~1.3 % for
  the Shizuku capture process (Mi 11).
* Wi-Fi: `WIFI_MODE_FULL_LOW_LATENCY` lock + partial wake lock only while a session runs;
  resend requests served from a 1024-packet backlog (~8 s).
* LogServer: now opt-in (settings → debug) in release builds, thread-safe, `/text` endpoint.
* **Android 11 (API 30, minSdk) smoke test** on an emulator: main screen, settings, tile picker,
  runtime permission + classic consent dialog, ALAC stream to shairport-sync — no crashes.
* **10-minute soak test** (screen off, phone dozing, ALAC to shairport-sync): 0 missing, 0 late,
  0 resend requests, receiver buffer steady at 113–122 packets, source drift ≈ 1–4 ppm.

## 5. Usability fixes
* One tap on a speaker = connect (old flow: select, then press "stream"; tapping the selected
  speaker silently deselected it). Tap more speakers for multi-room; per-speaker sliders.
* Clear per-speaker states: connecting, reconnecting, playing (codec + resend stats), password
  required (Digest auth dialog, lowercase then uppercase hex), "accept on the speaker",
  busy (453), unsupported (FairPlay-only, greyed out).
* Stale "disconnect" button after a failed connect — gone (UI is driven by session state).
* Emulator-only test device no longer shows on real phones.
* Streaming stops cleanly: the player is paused (like unplugging headphones) so audio never
  falls back to the phone speaker; phone volume restored, also after a crash.
* Setup card guides through permissions / Shizuku / notification access.

## 6. GitHub issues triage (Sept 2026)
| # | Report | Status in v2 |
|---|---|---|
| 1 | Use MediaRouteProvider instead of capture? | Routes can't carry audio for arbitrary apps; v2 uses routes as the *control* surface (system Output Switcher, §3.5). |
| 2 | Picking screen/app → nothing happens (Android 14) | Old flow dropped the projection; v2 acquires it in the FGS on the main thread, falls back cleanly, and Shizuku/app-op setup skips the dialog entirely. |
| 3 | Sonos doesn't show / work | Old build hid AirPlay-2-only devices. v2 lists them and routes Sonos to AirPlay 2 (transient pairing, NTP, **ALAC** like OwnTone — OwnTone streams to Sonos/IKEA Symfonisk this way; pyatv's PCM path is unverified on Sonos). RAOP fallback with MFi auth-setup (Sonos Beam 403 case). Not verified on hardware — needs a Sonos owner. |
| 4 | Docker receiver suggestion | Out of scope (receiver side). |
| 5 | "No stream" + IKEA/Sonos, Edifier (AirPlay 2) | Same as #2/#3. |
| 6 | Crash on start (Android 17: JmDNS `joinGroup` EPERM) | Discovery moved to NsdManager (system mDNS); JmDNS only as a guarded fallback. |
| 7 | Connects, no sound, Apple Music (S24) | Apple Music only allows capture *by system*. Privileged capture is limited by AOSP to 16 kHz mono (`AudioMix.canBeUsedForPrivilegedMediaCapture`), so it can't be streamed at music quality; v2 detects "playing but silent" and tells the user why. Spotify/YouTube Music/VLC etc. work. |

Sonos notes from OwnTone (`src/outputs/airplay.c`): sequence GET /info → pair (transient) →
SETUP session (NTP) → RECORD → SETUP stream (ALAC, ct=2, audioFormat 0x40000,
latencyMin 11025) → SET_PARAMETER volume **last** ("Sonos Symfonisk doesn't register the volume
otherwise" — v2 sets volume after connect). Sonos TXT example:
`manufacturer=Sonos model=Bookshelf features=0x445F8A00,0x1C340 srcvers=366.0`.

## 7. Roadmap / ideas (not done yet)

| Idea | Value | Effort | Notes |
|---|---|---|---|
| PTP timing for AirPlay 2 | Only shairport-sync's AP2 mode needs it today (it already gets AirPlay 1); insurance if Apple drops NTP | L | Deferred on purpose. Plan: act as PTP master — Announce/Sync/Follow_Up sent *to* receivers' 319/320 from ephemeral ports (nqptp only listens, so no root needed), SETUP with `timingProtocol: PTP` + `timingPeerInfo`, SETPEERS, 0xD7 sync packets carrying the clock ID (shairport rtp.c, OwnTone airplay.c `use_ptp`). |
| AirPlay 2 buffered audio (AAC/ALAC over TCP) | Much larger buffer, better for bad Wi-Fi; required by some Sonos firmware | L | type 103 stream, needs `setrateanchortime`. |
| Per-app capture | Stream only Spotify, keep notifications local | S | AudioPlaybackCapture `addMatchingUid` / AudioMixingRule `RULE_MATCH_UID`. |
| Receiver mode | Phone becomes an AirPlay speaker (e.g. old phone + hi-fi) | L | shairport-sync or libraop ported via NDK. |
| Root mode | Same as Shizuku without the adb step (Magisk `su` → start UserService as root) | S | Shizuku already supports root start. |
| AirPlay 2 FairPlay (et=5-only receivers, AirScreen) | Samsung TVs etc. | XL | Requires FairPlay SAP; see FAIRPLAY_HANDSHAKE.md — not feasible legally/technically for now. |

## 8. Test rig recipe
* Capture receiver: `shairport-sync -c /dev/null -a Name --port=5050 -o stdout > capture.raw`
  (macOS native build) or on the Pi with `output_backend = "stdout"`.
* Tone: stereo WAV with 1 kHz L / 1.5 kHz R; `tools/check_tone_capture.py` or windowed
  zero-crossing analysis. Byte-order/codec bugs show as noise, dropouts as zero runs.
* Metadata: enable `metadata.pipe_name` and read it with a detached reader (`setsid`).
* **Keep test tones quiet** and stop the player after tests — a failed reroute or ended
  session sends audio back to the phone speaker.
