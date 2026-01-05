# AirPlay Android

📱 Stream audio from your Android device to AirPlay speakers and receivers.

![Android](https://img.shields.io/badge/Android-10%2B-green)
![AirPlay](https://img.shields.io/badge/AirPlay-v1-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

## Features

- 🔊 **System Audio Capture** - Stream any audio playing on your device
- 🔍 **Auto Discovery** - Automatically find AirPlay devices on your network via mDNS/Bonjour
- 🔐 **Encrypted Streaming** - AES-128-CBC encryption with RSA key exchange
- ⏱️ **Synchronized Playback** - Proper RTP timing and sync packets for smooth audio
- 🎚️ **Volume Control** - Adjust volume on the AirPlay receiver

## Supported Protocols

| Protocol | Status | Notes |
|----------|--------|-------|
| AirPlay 1 (RAOP) | ✅ Working | L16 PCM audio, encrypted |
| AirPlay 2 | 🚧 In Progress | Coming soon |

## Requirements

- Android 10 (API 29) or higher
- AirPlay-compatible receiver (e.g., [shairport-sync](https://github.com/mikebrady/shairport-sync), Apple TV, HomePod, AirPort Express)

## Installation

### From Source

1. Clone the repository:
   ```bash
   git clone https://github.com/g8row/airplay-android.git
   cd airplay-android
   ```

2. Build with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install the APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### From Release

Download the latest APK from the [Releases](https://github.com/yourusername/airplay-android/releases) page.

## Usage

1. **Grant Permissions** - The app needs audio recording permission for system audio capture
2. **Start Media** - Play audio/video on your device
3. **Select Device** - Tap an AirPlay device from the discovered list
4. **Allow Capture** - Approve the screen/audio capture prompt
5. **Stream!** - Audio will now play through your AirPlay speaker

## How It Works

The app uses Android's `AudioPlaybackCapture` API (Android 10+) to capture system audio, then streams it to AirPlay receivers using the RAOP (Remote Audio Output Protocol):

```
┌─────────────┐     ┌──────────────┐     ┌────────────────┐
│   Android   │────▶│  AirPlay     │────▶│   AirPlay      │
│   Device    │     │  Streamer    │     │   Receiver     │
│  (Audio)    │ PCM │  (This App)  │ RTP │  (Speaker)     │
└─────────────┘     └──────────────┘     └────────────────┘
```

### Technical Details

- **Audio Format**: L16/44100/2 (16-bit PCM, 44.1kHz, stereo)
- **Transport**: RTP over UDP
- **Control**: RTSP over TCP (port 5000)
- **Encryption**: AES-128-CBC with RSA-OAEP key exchange
- **Timing**: NTP-style timestamps with sync packets

See [docs/AIRPLAY_PROTOCOL.md](docs/AIRPLAY_PROTOCOL.md) for detailed protocol documentation.

## Tested Receivers

- ✅ shairport-sync (v4.x)
- ✅ shairport-sync (v3.x)
- ⚠️ Apple TV (may require AirPlay 2)
- ⚠️ HomePod (requires AirPlay 2)

## Known Limitations

- **DRM Content**: Some apps block audio capture for DRM-protected content (Netflix, Spotify, etc.)
- **AirPlay 2**: Modern Apple devices may require AirPlay 2 which is still in development
- **Latency**: There's inherent ~2-3 second latency due to buffering requirements

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions and development notes.

### Project Structure

```
app/src/main/java/com/airplay/streamer/
├── MainActivity.kt          # Main UI
├── discovery/               # mDNS service discovery
├── raop/                    # AirPlay 1 (RAOP) client
│   ├── RaopClient.kt       # Main RAOP implementation
│   └── AlacEncoder.kt      # ALAC encoder (optional)
├── service/                 # Audio capture service
└── util/                    # Utilities
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [shairport-sync](https://github.com/mikebrady/shairport-sync) - Reference implementation and testing
- [Unofficial AirPlay Protocol Spec](https://nto.github.io/AirPlay.html) - Protocol documentation

---

**Disclaimer**: AirPlay is a trademark of Apple Inc. This project is not affiliated with or endorsed by Apple Inc.
