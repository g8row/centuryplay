# centuryplay - development guide

## Build Environment

### Prerequisites
- **Java JDK 17+** (JDK 21 recommended)
- **Android SDK** with:
  - Platform SDK 34 (Android 14)
  - Build Tools 34.0.0
  - Platform Tools (adb)

### Linux Setup

#### Install Java
```bash
# Debian/Ubuntu
sudo apt install openjdk-21-jdk

# Verify
java -version
```

#### Install Android SDK (Option 1: Android Studio)
```bash
# Via snap (recommended)
sudo snap install android-studio --classic

# Launch Android Studio and complete setup wizard to download SDK
# SDK will be installed to ~/Android/Sdk by default
```

#### Install Android SDK (Option 2: Command-line only)
```bash
# Create SDK directory
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools

# Download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip
mv cmdline-tools latest

# Add to PATH (add to ~/.bashrc or ~/.zshrc)
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Accept licenses and install components
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

#### Build Command (Linux)
```bash
cd /path/to/centuryplay
chmod +x gradlew   # First time only
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Windows Setup

#### Prerequisites
- Android Studio installed (includes JDK and SDK)

#### Build Command (PowerShell)
```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
```

---

## Deployment & Debugging

### Install APK

#### Linux
```bash
# Uninstall previous version (optional, clears app state)
adb uninstall com.airplay.streamer

# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Windows (PowerShell)
```powershell
# Uninstall
.\platform-tools\adb.exe uninstall com.airplay.streamer

# Install Debug APK
.\platform-tools\adb.exe install app\build\outputs\apk\debug\app-debug.apk
```

### Logging

#### Linux
```bash
# ADB Logcat (Last 2000 lines)
adb logcat -d -t 2000

# Filter for app logs
adb logcat -d -t 2000 | grep -E "AirPlay|RAOP|AudioCapture|RaopClient"
```

#### Windows (PowerShell)
```powershell
.\platform-tools\adb.exe logcat -d -t 2000 | Select-String -Pattern "AirPlay|RAOP|AudioCapture"
```

### Web Log Server
The app runs a web server for viewing logs in real-time:
- URL: `http://<PHONE_IP>:8080/`

---

## Docker Testing Environment

### **Shairport-sync (Local AirPlay Receiver)**

The project includes a `docker-compose.yml` for running a local AirPlay receiver using [shairport-sync](https://github.com/mikebrady/shairport-sync).

#### Prerequisites
- Docker Desktop for Windows with WSL2 backend
- Linux (in WSL2) for full mDNS support

#### Start the AirPlay Receiver
```bash
# In WSL2 terminal, navigate to project directory
cd /mnt/c/Users/g8row/Documents/airplay

# Start shairport-sync
docker compose up -d

# View logs
docker compose logs -f shairport-sync
```

#### Windows Limitations
- **mDNS Discovery**: Host networking doesn't work the same on Windows/WSL2. The container may not be discoverable automatically.
- **Workaround**: Run shairport-sync directly in WSL2 without Docker, or use a Linux VM.

#### WSL2 Native Install (Alternative)
```bash
# In WSL2 Ubuntu
sudo apt-get update
sudo apt-get install shairport-sync

# Run with debug output (dummy - no audio)
shairport-sync -v --statistics -o dummy

# Run with actual audio (PulseAudio - requires WSLg or PulseAudio server)
shairport-sync -v --statistics -o pa
```

#### Linux/Laptop Setup (With Audio Output)
On a real Linux machine or laptop with speakers:
```bash
# Install
sudo apt-get update
sudo apt-get install shairport-sync pulseaudio

# Start PulseAudio if not running
pulseaudio --start

# Run shairport-sync with PulseAudio output
shairport-sync --name="Laptop-AirPlay" -v -o pa

# Or with ALSA (direct to sound card, no PulseAudio)
shairport-sync --name="Laptop-AirPlay" -v -o alsa
```

**Port forwarding** (if behind NAT/firewall):
- Port 5000/TCP (RTSP)
- Ports 6000-6010/UDP (RTP audio)

---

## Traffic Capture & Analysis

### **Capture AirPlay Traffic**

#### Using Docker (Linux/WSL2)
```bash
# Start capture container alongside shairport
docker compose --profile capture up -d

# Captures are saved to ./captures/
```

#### Using Wireshark on Windows
1. Open Wireshark
2. Select your network interface (WiFi or Ethernet)
3. Apply filter: `tcp.port == 7000 or tcp.port == 5000 or udp.port == 5353`
4. Start capture
5. Cast audio from Apple Music or this app to an AirPlay device

#### Analyzing Apple Music Traffic
The Apple Music Windows app supports AirPlay. To analyze its traffic:

1. Start Wireshark capture
2. Open Apple Music and play audio
3. Click the AirPlay icon and select a device
4. Observe the traffic in Wireshark
5. Look for:
   - HTTP requests to port 7000 (AirPlay 2 control)
   - RTSP requests to port 5000 (AirPlay 1/RAOP)
   - TLV8-encoded pairing data

### **Useful Wireshark Filters**
```
# All AirPlay traffic
tcp.port == 7000 or tcp.port == 5000

# mDNS discovery
udp.port == 5353

# RTP audio packets
udp.portrange 6000-6005

# Pair-Setup/Verify
http.request.uri contains "pair"
```

---

## Protocol Documentation
See [docs/AIRPLAY_PROTOCOL.md](docs/AIRPLAY_PROTOCOL.md) for detailed protocol documentation.
