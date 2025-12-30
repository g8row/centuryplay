# AirPlay Streamer - Development Guide

## Build Environment
The project requires a specific JDK (bundled with Android Studio) and Android SDK.
Use the following PowerShell command to build, ensuring the environment variables are set correctly for the session.

### **Build Command (PowerShell)**
```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"; $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --no-daemon
```

*Note: Accessing the `jbr` directory requires quotes due to spaces in "Program Files".*

## Deployment & Debugging

### **Uninstall & Install**
It is recommended to uninstall the previous version before installing a new debug build to clear app state.

```powershell
# Uninstall
.\platform-tools\adb.exe uninstall com.airplay.streamer

# Install Debug APK
.\platform-tools\adb.exe install app\build\outputs\apk\debug\app-debug.apk
```

### **Logging**
Retrieve logs using ADB or the built-in Web Log Server (if accessible).

```powershell
# ADB Logcat (Last 2000 lines)
.\platform-tools\adb.exe logcat -d -t 2000

# Filter for just our app
.\platform-tools\adb.exe logcat -d -t 2000 | Select-String -Pattern "AirPlay|RAOP|AudioCapture"
```

### **Web Log Server**
*   URL: `http://<PHONE_IP>:8080/`

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
