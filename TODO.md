# centuryplay - Roadmap & TODO

## 🎯 v0.3 - AirPlay 2 & Media Info

### AirPlay 2 Protocol (High Priority)
- [ ] **SRP-6a Pairing** - Implement Secure Remote Password protocol for PIN pairing
  - [ ] Generate ephemeral keys (a, A = g^a mod N)
  - [ ] Handle server's B value and salt
  - [ ] Compute session key K from shared secret S
  - [ ] Verify M1/M2 proof exchange
- [ ] **Ed25519 Signatures** - Sign pairing messages
- [ ] **Curve25519 Key Exchange** - Derive encryption keys
- [ ] **ChaCha20-Poly1305** - Encrypt control channel after pairing
- [ ] **TLV8 Encoding** - HomeKit Accessory Protocol message format
- [ ] **HTTP Control** (port 7000) - Replace RTSP with HTTP endpoints
  - [ ] POST /pair-setup
  - [ ] POST /pair-verify  
  - [ ] POST /fp-setup (FairPlay)
  - [ ] POST /auth-setup
  - [ ] SETUP /stream (upgrade to data channel)
- [ ] **Buffered Audio** - AirPlay 2 uses buffered streaming vs realtime
- [ ] **Multi-room Support** - Send to multiple speakers simultaneously

### Media Info Display
- maybe put it as the same size as the list of devices, make them switchable like androids app switcher?
- [ ] **MediaSession Integration** - Listen for active media sessions
  - [ ] Song title, artist, album
  - [ ] Album art (Bitmap)
  - [ ] Playback state (playing/paused)
  - [ ] Duration and position
- [ ] **Now Playing UI** - Show in controls card when streaming
  - [ ] Marquee text for long titles
  - [ ] Album art thumbnail (rounded corners)
  - [ ] Artist • Album subtitle

### ALAC Encoding (Optional)
- [ ] **Apple Lossless Codec** - Alternative to L16 PCM
  - [ ] Lower bandwidth (40-60% of PCM)
  - [ ] Some receivers prefer ALAC
- [ ] **AlacEncoder.kt** - Already exists, needs integration
- [ ] **SDP Negotiation** - Announce ALAC capability in ANNOUNCE
- [ ] **Codec Selection UI** - Settings option: Auto/PCM/ALAC

---

## 🔮 v0.4 - Quality of Life

### Audio Quality
- [ ] **Sample Rate Selection** - 44.1kHz / 48kHz / 96kHz options
- [ ] **Bit Depth Options** - 16-bit / 24-bit
- [ ] **Latency Tuning** - Adjustable buffer size slider
- [ ] **Audio Statistics** - Show bitrate, dropped packets, latency

### UI Enhancements
- [ ] **Speaker Groups** - Create and manage speaker groups
- [ ] **Favorites** - Star frequently used speakers
- [ ] **Recently Connected** - Quick access to last speakers
- [ ] **Dark/Light Theme Toggle** - Override system theme
- [ ] **Widget** - Home screen widget for quick streaming
- [ ] **Quick Settings Tile** - Toggle streaming from notification shade

### Connection Features
- [ ] **Auto-reconnect** - Reconnect when speaker comes back online
- [ ] **Connection History** - Log of past connections with stats
- [ ] **Network Diagnostics** - Test connectivity to speaker
- [ ] **Manual IP Entry Improvement** - Save manual devices persistently

---

## 🚀 v0.5 - Advanced Features

### Casting Sources
- [ ] **Microphone Input** - Stream mic audio (karaoke mode)
- [ ] **Audio File Playback** - Stream local audio files
- [ ] **URL Streaming** - Stream from HTTP audio URLs

### Protocol Extensions
- [ ] **AirPlay Video** - Screen mirroring (complex)
- [ ] **Chromecast Support** - Cast to Google devices
- [ ] **DLNA/UPnP** - Universal media streaming
- [ ] **Snapcast** - Multi-room sync protocol

### System Integration
- [ ] **Tasker/Automate Plugin** - Automation integration
- [ ] **Android Auto** - Stream in car mode
- [ ] **Wear OS Companion** - Control from smartwatch

---

## 🐛 Known Issues & Tech Debt

### Bugs to Fix
- [ ] AirScreen (Android receiver) compatibility issues
- [ ] Some speakers disconnect after ~30 minutes
- [ ] Volume control granularity (some speakers ignore small changes)

### Code Quality
- [ ] Unit tests for RaopClient
- [ ] Integration tests with mock server
- [ ] Ktlint/Detekt code style enforcement
- [ ] Migrate to Jetpack Compose (future)
- [ ] Modularize into :app, :protocol, :discovery modules

### Documentation
- [ ] Code documentation (KDoc)
- [ ] Architecture diagram
- [ ] Contributing guide
- [ ] Protocol implementation notes

---

## 📚 Resources & References

### AirPlay 2 Documentation
- [unofficial-airplay2-protocol](https://github.com/openairplay/airplay2-receiver) - Python implementation
- [airplay2-receiver](https://github.com/postlund/pyatv) - pyatv library
- [HomeKit ADK](https://github.com/apple/HomeKitADK) - Apple's HAP reference

### Crypto Libraries
- BouncyCastle - RSA, AES (already using)
- [AirTunes4J](https://github.com/pentateu/AirTunes4J) - Java RAOP reference
- Need: Ed25519, Curve25519, ChaCha20-Poly1305

### Testing
- shairport-sync - AirPlay 1 receiver (current)
- [uxplay](https://github.com/FDH2/UxPlay) - AirPlay 2 receiver for Linux
- Apple TV / HomePod - Real AirPlay 2 devices

---

## 🏃 Current Sprint: AirPlay 2 Foundation

### Phase 1: Crypto Setup ✅ DONE
1. ✅ Add Ed25519/Curve25519 (BouncyCastle already has them)
2. ✅ Implement TLV8 encoder/decoder
3. ✅ Create AirPlay2Crypto utility class
4. ✅ Implement HKDF key derivation
5. ✅ Implement ChaCha20-Poly1305 AEAD

### Phase 2: Pairing Flow ⬅️ IN PROGRESS
1. ✅ Update AirPlayAuth with proper M1-M6 flow
2. [ ] Test against real AirPlay 2 device
3. [ ] Handle PIN entry UI
4. [ ] Store paired device credentials persistently

### Phase 3: Streaming
1. [ ] Implement pair-verify for reconnection
2. [ ] Set up encrypted control channel
3. [ ] Adapt audio streaming for AirPlay 2 format
