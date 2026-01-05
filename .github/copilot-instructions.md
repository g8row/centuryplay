# centuryplay - AI Coding Instructions

Android app that streams system audio to AirPlay speakers via the RAOP protocol.

## Architecture Overview

```
MainActivity → MainViewModel → AudioCaptureService → RaopClient → AirPlay Receiver
                    ↓
            AirPlayDiscovery (mDNS/jmDNS)
```

### Source Code Structure

```
app/src/main/java/com/airplay/streamer/
├── MainActivity.kt              # Main UI, speaker selection, streaming controls
├── SettingsActivity.kt          # App settings (protocol preference, etc.)
├── discovery/
│   ├── AirPlayDevice.kt         # Data class for discovered devices
│   └── AirPlayDiscovery.kt      # mDNS discovery using jmDNS
├── raop/
│   ├── RaopClient.kt            # AirPlay 1 (RTSP + RTP), fully working
│   ├── AirPlay2Client.kt        # AirPlay 2 (HTTP + SRP), work in progress
│   └── AlacEncoder.kt           # ALAC encoder (optional, not currently used)
├── service/
│   └── AudioCaptureService.kt   # Foreground service for AudioPlaybackCapture
├── ui/
│   ├── MainViewModel.kt         # StateFlow-based state management
│   ├── SpeakerAdapter.kt        # RecyclerView adapter for speaker list
│   └── WavySlider.kt            # Custom animated volume slider view
└── util/
    └── LogServer.kt             # HTTP debug log server at :8080
```

## Build & Deploy

```bash
# Debug build
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build (requires signing)
./gradlew assembleRelease

# View logs
adb logcat -d -t 2000 | grep -E "AirPlay|RAOP|AudioCapture|RaopClient"

# Web logs (while app is running)
curl http://<phone-ip>:8080/
```

**Requirements:** JDK 17+, Android SDK 34+, `minSdk = 29` (Android 10)

## Protocol Details

### AirPlay 1 (RAOP) - Fully Implemented

Flow in `RaopClient.kt`:
1. **RTSP session** (TCP 5000): OPTIONS → ANNOUNCE → SETUP → RECORD
2. **UDP streams**:
   - Timing port: Bidirectional NTP-style sync
   - Control port: Sync packets with playback timing
   - Audio port: RTP packets with encrypted PCM
3. **Encryption**: AES-128-CBC with RSA key exchange (Apple's public key)

**Audio format:** L16/44100/2 (16-bit PCM, 44.1kHz, stereo), 352 frames/packet

See [docs/AIRPLAY_PROTOCOL.md](docs/AIRPLAY_PROTOCOL.md) for comprehensive reference.

### AirPlay 2 - Work in Progress

Located in `AirPlay2Client.kt`. Requires:
- HTTP control (port 7000)
- SRP-6a pairing with PIN
- Ed25519 + Curve25519 for encryption
- HomeKit Accessory Protocol (HAP) TLV8 encoding

## Key Patterns

### Concurrency
- **Coroutines**: All network I/O uses `Dispatchers.IO` via `withContext`
- **StateFlow**: UI state in `MainViewModel` observed by `MainActivity`
- **AtomicBoolean**: Thread-safe flags (`isConnected`, `isStreaming`)
- **callbackFlow**: Device discovery emits events as a Flow

### UI Architecture
- **ViewBinding**: All UI uses generated binding classes, no `findViewById`
- **Material Design 3**: Expressive theme with dynamic colors
- **Custom Views**: `WavySlider.kt` with Canvas drawing and ValueAnimator
- **Animations**: ChangeBounds transitions with OvershootInterpolator

### Error Handling
- Health monitor thread detects server disconnects via socket read
- Multicast lock checked before release to prevent crashes
- RecyclerView item animations disabled to prevent rapid-tap crashes

## UI Components

### MainActivity
- Speaker list with `RecyclerView` and `SpeakerAdapter`
- Stream button with start/stop states
- `WavySlider` for volume (visible only when streaming)
- Connection indicator with pulse animation
- Bouncy expand/collapse animations

### SpeakerAdapter
- Card-based speaker items with stroke selection indicator
- Protocol badge (v1/v2) as styled TextView with rounded background
- No item animations (disabled to prevent crashes)

### WavySlider
Custom view with animated sine wave:
- `ValueAnimator` for wave phase animation (40dp/sec)
- Canvas drawing with `Path.quadTo()` for waves
- Touch handling for volume changes
- Padding-based thumb positioning

## Testing Environment

Run local AirPlay receiver with shairport-sync:
```bash
# Build from source
git clone https://github.com/mikebrady/shairport-sync.git
cd shairport-sync
./configure --with-ssl=openssl --with-avahi --with-pa --with-alsa
make

# Run with PulseAudio output and verbose logging
shairport-sync --name="test" -vvvv -o pa 2>&1 | tee /tmp/shairport.log
```

## Important Constraints

- **DRM content**: Netflix, Spotify, etc. block `AudioPlaybackCapture`
- **Hi-Res audio**: 96kHz+ gets downsampled by Android to 48kHz before capture
- **AirPlay 2 devices**: HomePod, modern Apple TV need `AirPlay2Client` (WIP)
- **Package name**: `com.airplay.streamer`

## Common Issues & Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| MulticastLock crash | Releasing unheld lock | Check `lock.isHeld` before release |
| RecyclerView crash | Item animations + rapid taps | Set `itemAnimator = null` |
| Notification recreates activity | Missing launchMode | Add `android:launchMode="singleTask"` |
| Health monitor not starting | Previous instance running | Stop existing monitor before start |

## Version History

- **v0.2**: Wavy slider, MD3 UI, bouncy animations, health monitoring, crash fixes
- **v0.1**: Initial AirPlay 1 implementation with basic discovery
