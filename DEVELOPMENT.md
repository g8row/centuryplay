# development guide

## build environment

### prerequisites
- **java jdk 17+** (jdk 21 recommended)
- **android sdk** with:
  - platform sdk 35 (android 15)
  - build tools 35.0.0
  - platform tools (adb)

### linux setup

#### install java
```bash
# debian/ubuntu
sudo apt install openjdk-21-jdk

# verify
java -version
```

#### install android sdk (option 1: android studio)
```bash
# via snap (recommended)
sudo snap install android-studio --classic

# launch android studio and complete setup wizard to download sdk
# sdk will be installed to ~/Android/Sdk by default
```

#### install android sdk (option 2: command-line only)
```bash
# create sdk directory
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools

# download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip
mv cmdline-tools latest

# add to path (add to ~/.bashrc or ~/.zshrc)
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# accept licenses and install components
sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

#### build command (linux)
```bash
cd /path/to/centuryplay
chmod +x gradlew   # first time only
./gradlew assembleDebug
```

apk location: `app/build/outputs/apk/debug/app-debug.apk`

### windows setup

#### prerequisites
- android studio installed (includes jdk and sdk)

#### build command (powershell)
```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
```

---

## deployment & debugging

### install apk

#### linux
```bash
# uninstall previous version (optional, clears app state)
adb uninstall com.airplay.streamer

# install debug apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### windows (powershell)
```powershell
# uninstall
.\platform-tools\adb.exe uninstall com.airplay.streamer

# install debug apk
.\platform-tools\adb.exe install app\build\outputs\apk\debug\app-debug.apk
```

### logging

#### linux
```bash
# adb logcat (last 2000 lines)
adb logcat -d -t 2000

# filter for app logs
adb logcat -d -t 2000 | grep -E "AirPlay|RAOP|AudioCapture|RaopClient"
```

#### windows (powershell)
```powershell
.\platform-tools\adb.exe logcat -d -t 2000 | Select-String -Pattern "AirPlay|RAOP|AudioCapture"
```

### web log server
the app runs a web server for viewing logs in real-time:
- url: `http://<PHONE_IP>:8080/`

---

## docker testing environment

### **shairport-sync (local airplay receiver)**

the project includes a `docker-compose.yml` for running a local airplay receiver using [shairport-sync](https://github.com/mikebrady/shairport-sync).

#### prerequisites
- docker desktop for windows with wsl2 backend
- linux (in wsl2) for full mdns support

#### start the airplay receiver
```bash
# in wsl2 terminal, navigate to project directory
cd /mnt/c/Users/g8row/Documents/airplay

# start shairport-sync
docker compose up -d

# view logs
docker compose logs -f shairport-sync
```

#### windows limitations
- **mdns discovery**: host networking doesn't work the same on windows/wsl2. the container may not be discoverable automatically.
- **workaround**: run shairport-sync directly in wsl2 without docker, or use a linux vm.

#### wsl2 native install (alternative)
```bash
# in wsl2 ubuntu
sudo apt-get update
sudo apt-get install shairport-sync

# run with debug output (dummy - no audio)
shairport-sync -v --statistics -o dummy

# run with actual audio (pulseaudio - requires wslg or pulseaudio server)
shairport-sync -v --statistics -o pa
```

#### linux/laptop setup (with audio output)
on a real linux machine or laptop with speakers:
```bash
# install
sudo apt-get update
sudo apt-get install shairport-sync pulseaudio

# start pulseaudio if not running
pulseaudio --start

# run shairport-sync with pulseaudio output
shairport-sync --name="Laptop-AirPlay" -v -o pa

# or with alsa (direct to sound card, no pulseaudio)
shairport-sync --name="Laptop-AirPlay" -v -o alsa
```

**port forwarding** (if behind nat/firewall):
- port 5000/tcp (rtsp)
- ports 6000-6010/udp (rtp audio)

---

## traffic capture & analysis

### **capture airplay traffic**

#### using docker (linux/wsl2)
```bash
# start capture container alongside shairport
docker compose --profile capture up -d

# captures are saved to ./captures/
```

#### using wireshark on windows
1. open wireshark
2. select your network interface (wifi or ethernet)
3. apply filter: `tcp.port == 7000 or tcp.port == 5000 or udp.port == 5353`
4. start capture
5. cast audio from apple music or this app to an airplay device

#### analyzing apple music traffic
the apple music windows app supports airplay. to analyze its traffic:

1. start wireshark capture
2. open apple music and play audio
3. click the airplay icon and select a device
4. observe the traffic in wireshark
5. look for:
   - http requests to port 7000 (airplay 2 control)
   - rtsp requests to port 5000 (airplay 1/raop)
   - tlv8-encoded pairing data

### **useful wireshark filters**
```
# all airplay traffic
tcp.port == 7000 or tcp.port == 5000

# mdns discovery
udp.port == 5353

# rtp audio packets
udp.portrange 6000-6005

# pair-setup/verify
http.request.uri contains "pair"
```

---

## protocol documentation
see [docs/airplay_protocol.md](docs/AIRPLAY_PROTOCOL.md) for detailed protocol documentation.
