# VoxLink

یک برنامهٔ اندرویدی سبک برای ارتباط صوتی لحظه‌ای (Walkie-Talkie) روی شبکهٔ وای‌فای محلی، بدون نیاز به اینترنت یا سرور مرکزی.

**VoxLink** lets a group of Android devices on the same Wi-Fi network talk to each other like a walkie-talkie, using multicast UDP for voice and a separate UDP broadcast for host discovery.

---

## Features

- **Create Group (Host):** start a new voice room and let others discover you on the local network.
- **Join Group (Client):** search for an active host and join the room automatically.
- **Push-to-Talk (PTT):** hold the big button to speak, release to stop.
- **Always-On Mic:** toggle microphone mode so you don't have to hold the button.
- **Live Member List:** see who is in the room, who is the host (👑 مدیر), and who is currently speaking.
- **Dark Mode:** switch between dark and light themes from the settings drawer.
- **Custom Username:** set your display name before entering a room.
- **No Internet Required:** everything runs over local Wi-Fi / multicast.

---

## Tech Stack

- **Language:** Java 17
- **Build Tool:** Gradle 8.7
- **Android Gradle Plugin:** 8.5.0
- **Compile SDK / Target SDK:** 34
- **Min SDK:** 24 (Android 7.0+)
- **Networking:** Multicast UDP (`MulticastSocket`, `NetworkInterface`)
- **Audio:** `AudioRecord` / `AudioTrack` (PCM 16-bit, 16 kHz, mono)

---

## Project Structure

```
VoxLink/
├── app/
│   ├── build.gradle
│   └── src/main/java/com/example/sample/
│       ├── MainActivity.java          # Launcher, role selection, permissions
│       ├── VoiceRoomActivity.java      # Voice room UI, member list, PTT
│       ├── DiscoveryManager.java       # UDP broadcast host discovery
│       ├── UdpSender.java              # Multicast voice/control packet sender
│       ├── UdpReceiver.java            # Multicast voice/control packet receiver
│       ├── AudioRecorder.java          # Microphone recording thread
│       ├── AudioPlayer.java            # Incoming audio playback
│       ├── VoxToast.java               # Custom styled toast messages
│       ├── NetworkHelper.java          # Wi-Fi / hotspot network interface detection
│       └── Constants.java              # Ports, sample rate, packet sizes
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/wrapper/
```

---

## Network Details

| Constant | Value | Description |
|----------|-------|-------------|
| `MULTICAST_GROUP` | `239.255.42.99` | Multicast address used for voice/control traffic |
| `UDP_PORT` | `50005` | Port used for multicast voice and control packets |
| `DISCOVERY_PORT` | `8888` | Port used for UDP broadcast host discovery |
| `SAMPLE_RATE` | `16000` | Audio sample rate (Hz) |
| `FRAME_SIZE` | `320` | Bytes per audio frame sent over the network |

- **Discovery:** the host broadcasts `VoxLink-Host: <port>` every 2 seconds to the Wi-Fi or hotspot broadcast address.
- **Voice:** each device joins the multicast group `239.255.42.99:50005`, sends its own audio, and plays audio from everyone else.
- **Control packets:** small packets for ping, join/leave events, and room destruction.
- **Network selection:** `NetworkHelper` detects the active Wi-Fi / hotspot (`wlan`, `ap`, `eth`, etc.) interface and computes the correct broadcast address, so the host can be the device acting as a mobile hotspot.

---

## Permissions

The app requests the following permissions at runtime:

- `RECORD_AUDIO` — to capture your voice
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` — to check Wi-Fi connectivity
- `CHANGE_WIFI_MULTICAST_STATE` — to enable multicast on supported devices
- `MODIFY_AUDIO_SETTINGS` — to route audio to the speaker
- `NEARBY_WIFI_DEVICES` (Android 13+) or `ACCESS_FINE_LOCATION` (older Android) — for local network discovery
- `WAKE_LOCK` / `FOREGROUND_SERVICE` — to keep the session alive while the app is in use

> **Important:** The app works when all devices are on the same local network — either connected to the same Wi-Fi router, or one device acting as a Wi-Fi hotspot (mobile AP) and the others connected to it. On the host/hotspot device, make sure the hotspot is on before tapping **Create Group**.

---

## Build

### Windows

```powershell
.\gradlew assembleDebug
```

### Linux / macOS

```bash
./gradlew assembleDebug
```

### Release

For a release APK, create a signing keystore and configure it in `app/build.gradle`, then run:

```bash
./gradlew assembleRelease
```

> The debug APK is signed with the default Android debug keystore and is suitable for testing.

---

## Install

Prebuilt APKs are available in the `releases/` folder:

| File | Description |
|------|-------------|
| `releases/VoxLink-v1.0-debug.apk`   | Debug build, signed with Android debug key |
| `releases/VoxLink-v1.0-release.apk` | Release build, signed with Android debug key (for testing) |

Install with `adb`:

```bash
adb install releases/VoxLink-v1.0-release.apk
```

Or copy the APK to your device and install it manually (allow *Install from unknown sources* if prompted).

---

## Usage

1. Make sure all devices are on the **same local network**:
   - Connect everyone to the same Wi-Fi router, **or**
   - Turn on a mobile hotspot on the host/manager device and connect the other devices to it.
2. Open VoxLink.
3. On the host/manager device, tap **Create Group (Host)**.
4. On the other devices, tap **Join Group** and wait for the room to be discovered.
5. In the voice room:
   - **Push-to-Talk:** hold the large button while speaking.
   - **Always On:** tap the mic toggle to keep the microphone open.
   - **Leave Room:** tap the leave button to exit.

---

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| "وای‌فای متصل نیست" / WiFi not connected | Device not on Wi-Fi | Connect to a Wi-Fi network first |
| Group not found | Devices on different networks / multicast disabled | Ensure same network; some routers block multicast |
| No incoming audio | Network firewall or multicast blocked | Check router settings; use a private/hotspot network |
| Microphone permission denied | Permission not granted | Grant microphone permission in Android Settings |

---

## Architecture Notes

- `DiscoveryManager` sends/receives UDP broadcast beacons on port `8888` to locate the host.
- `UdpSender` builds packets with an 8-byte header (`senderId`, sequence, length) and sends them to the multicast group.
- `UdpReceiver` joins the multicast group on the active Wi-Fi network interface (using `NetworkInterface` on Android 8+ for better reliability) and forwards audio/control data to `VoiceRoomActivity`.
- `AudioRecorder` reads PCM data from `AudioRecord` and passes frames to `UdpSender`.
- `AudioPlayer` receives incoming audio frames from `UdpReceiver` and writes them to `AudioTrack` for playback.
- `VoiceRoomActivity` coordinates the UI, updates the member list, handles PTT/toggle logic, and manages lifecycle cleanup.

---

## Recent Fixes

- Removed dead NSD-related constants and replaced them with the actual voice UDP port.
- Fixed broadcast address byte-order calculation in `DiscoveryManager`.
- Modernized multicast join/leave to use `NetworkInterface` on Android API 26+.
- Added member-ID payloads to join/leave control packets so identities propagate correctly across the group.
- Added `pendingPermissionAction` so permission results correctly trigger the chosen action (create/join).
- Restricted Wi-Fi check to `TRANSPORT_WIFI` to avoid false positives on other networks.

---

## License

This project is provided as-is for educational and personal use. You may modify and distribute it under the terms of your choice.

---

**Developed by:** Amir

**GitHub:** https://github.com/AmousaviNezhod/VoxLink
