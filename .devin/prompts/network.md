# VoxLink Network & Service Refactor Task

You are implementing the network and service layer of an Android intercom/walkie-talkie app.

## Repo & Branch
- Repository: `AmousaviNezhod/VoxLink`
- Start from branch: `devin/refactor-base`
- Create a new branch from it, e.g. `devin/network-service`
- Push your final work to that branch and run `gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug` (Linux/macOS) before pushing. Report the branch name and any build errors.

## Top-level goals (parent direction)
- Priority 1: **range / speed / device compatibility** on local Wi-Fi / hotspot.
- Priority 2: **basic host access control** so the manager can mute/kick users.
- Priority 3: **minimal security** — do not add encryption or complex auth.

## Files you may edit
- `app/src/main/java/com/example/sample/network/NetworkHelper.java`
- `app/src/main/java/com/example/sample/network/UdpSender.java`
- `app/src/main/java/com/example/sample/network/UdpReceiver.java`
- `app/src/main/java/com/example/sample/network/DiscoveryManager.java`
- `app/src/main/java/com/example/sample/service/VoxLinkService.java`
- `app/src/main/java/com/example/sample/util/Constants.java` (only to add missing network-related constants)
- `app/src/main/AndroidManifest.xml` (only if you must add service/receiver declarations; the service is already declared)

DO NOT modify `MainActivity.java`, `VoiceRoomActivity.java`, `AudioPlayer.java`, `AudioRecorder.java`, `AudioMixer.java`, `AudioEffects.java`, or any UI resource files.

## Current state
The base branch has the package structure ready:
- `network`: `NetworkHelper`, `UdpSender`, `UdpReceiver`, `DiscoveryManager`
- `service`: `VoxLinkService` (empty skeleton)
- `model`: `Member`, `Room`, `AudioPacket`
- `util`: `Constants` (already contains control packet byte constants)
- `audio`: `AudioPlayer`, `AudioRecorder`, `AudioMixer`, `AudioEffects` skeletons with the public API defined; you may use `AudioMixer` and `AudioEffects` only via their public constructors/methods — do not change them.

Read every file in the base branch before editing.

## 1. NetworkHelper improvements
- Provide correct interface selection for Wi-Fi / hotspot / AP on Android.
- Method signatures:
  - `NetworkInterface getLocalNetworkInterface(Context)`
  - `InetAddress getLocalInetAddress(Context)`
  - `InetAddress getBroadcastAddress(Context)`
  - `boolean isLocalNetworkAvailable(Context)`
- Implementation:
  - On API 23+, prefer `ConnectivityManager.getActiveNetwork()` + `getLinkProperties()` to read the default interface name, then `NetworkInterface.getByName()`.
  - Fall back to `NetworkInterface.getNetworkInterfaces()` enumeration, preferring names containing `wlan`, `ap`, `eth`, `wlo`, `wl`, `ppp` (but not loopback).
  - Return `null` instead of a wrong interface.
  - `getBroadcastAddress` should use `InterfaceAddress.getBroadcast()` of the chosen interface; if not found, return `null`.

## 2. UdpSender
- Use `MulticastSocket` (extends `DatagramSocket`) and call `setReuseAddress(true)` and `setNetworkInterface(NetworkInterface)`.
- Send datagrams to `Constants.MULTICAST_GROUP` on `Constants.UDP_PORT`.
- Header (8 bytes): `senderId` (4 bytes), `sequence` (2 bytes unsigned), `payload length` (2 bytes unsigned).
- Generate a **positive** `senderId` in range `[1, Integer.MAX_VALUE)` using `SecureRandom` or `ThreadLocalRandom`. **Fix the old `Math.abs(UUID.hashCode())` bug** where `Math.abs(Integer.MIN_VALUE)` stays negative.
- Provide:
  - `boolean prepare()`
  - `void sendAudio(byte[] pcm)` — increments an `AtomicInteger` audio sequence for each call
  - `void sendControl(byte[] data)` — sends control data; sequence can be 0
  - `void close()`
  - `int getSenderId()`
  - `boolean isReady()`
- Do not send from the audio thread if not ready.

## 3. UdpReceiver
- Use `MulticastSocket`, `setReuseAddress(true)`, `setNetworkInterface`, acquire `WifiManager.MulticastLock`.
- Join `Constants.MULTICAST_GROUP` on `Constants.UDP_PORT`.
- Parse the 8-byte header and dispatch:
  - If payload length is small (control packets: 1 or `Constants.MEMBER_CONTROL_PACKET_SIZE` bytes), call `ControlPacketListener.onControlPacket(senderId, payload)`.
  - For larger payloads (audio), call `AudioPacketListener.onAudioPacket(senderId, sequence, payload)`.
- Ignore packets whose `senderId == mySenderId` (loopback own packets).
- Provide:
  - `boolean start(int mySenderId)`
  - `void stop()`
  - `void setAudioPacketListener(AudioPacketListener l)`
  - `void setControlPacketListener(ControlPacketListener l)`
- Define nested listener interfaces inside `UdpReceiver`:
  - `interface AudioPacketListener { void onAudioPacket(int senderId, int sequence, byte[] payload); }`
  - `interface ControlPacketListener { void onControlPacket(int senderId, byte[] payload); }`

## 4. DiscoveryManager
- Make host discovery reliable on hotspot / Wi-Fi.
- Host behavior:
  - Create a `DatagramSocket` bound to `Constants.DISCOVERY_PORT` (8888), `setReuseAddress(true)`, `setBroadcast(true)`.
  - Listen for probe packets containing the exact string `"VoxLink-Probe"`.
  - When a probe is received, reply unicast to the sender with `"VoxLink-Host:" + Constants.UDP_PORT`.
  - Also periodically broadcast a beacon to the subnet broadcast address from `NetworkHelper.getBroadcastAddress()` (fallback to `255.255.255.255` only if null). Interval: ~2 seconds.
- Client behavior:
  - Send `"VoxLink-Probe"` as a broadcast to port 8888.
  - Listen on a temporary/ephemeral port for unicast replies beginning with `"VoxLink-Host:"`.
  - Collect replies for a short timeout (e.g. 3 seconds) and pick the first host (or the one with the best signal if you can read RSSI, otherwise first).
  - Deliver `DiscoveryListener.onGroupFound(hostAddress, Constants.UDP_PORT)`.
- Provide `registerGroup()`, `startDiscovery()`, `stopDiscovery()`, `stopAll()`.
- Keep `DiscoveryListener` interface as is:
  - `onGroupFound(String hostAddress, int port)`
  - `onGroupLost()`
  - `onError(String message)`

## 5. VoxLinkService
Implement a foreground service that owns the network layer.
- Extend `Service`. Use `startForeground(...)` with a notification in `onCreate()` or when `startRoom()` is called.
- Foreground service type: `microphone`.
- Acquire and release:
  - `PowerManager.WakeLock` (SCREEN_DIM or PARTIAL_WAKE_LOCK)
  - `WifiManager.MulticastLock`
- Expose a `LocalBinder` with these methods:
  - `void startRoom(String username, boolean isHost, String hostAddress)`
  - `void leaveRoom()`
  - `void setPttPressed(boolean pressed)`
  - `void toggleAlwaysOn()`
  - `void muteMember(int memberId, boolean muted)` (no-op if not host, but parse request)
  - `void kickMember(int memberId)` (no-op if not host)
  - `Room getRoom()`
  - `void setListener(VoxLinkListener listener)`
- VoxLinkListener interface (nested in VoxLinkService):
  - `void onMemberJoined(Member member)`
  - `void onMemberLeft(int memberId)`
  - `void onMemberSpeaking(int memberId, boolean speaking)`
  - `void onHostChanged(int hostId)`
  - `void onRoomDestroyed()`
  - `void onError(String message)`
- Lifecycle:
  - `startRoom` creates `UdpSender`, `UdpReceiver`, and `Room`.
  - Host: also start `DiscoveryManager.registerGroup()` and send `PACKET_PING_HOST` every `Constants.PING_INTERVAL_MS`.
  - Client: if `hostAddress` is provided, send `PACKET_PING_CLIENT` every `Constants.PING_INTERVAL_MS`; otherwise you may start a short discovery (but this is usually done by UI).
  - For audio playback, create `AudioPlayer` and `AudioMixer` and call `audioMixer.start()`. When an audio packet arrives, call `audioMixer.enqueue(senderId, sequence, payload)`.
  - For audio recording, create `AudioRecorder` and set its listener so that on each chunk it calls `udpSender.sendAudio(chunk)`. Start/stop recording based on `setPttPressed` / `toggleAlwaysOn`.
  - `leaveRoom` stops pinger, sends `PACKET_CLIENT_LEAVE` (or `PACKET_ROOM_DESTROYED` if host), stops `AudioRecorder`, `AudioMixer`, `AudioPlayer`, `UdpReceiver`, `DiscoveryManager`, releases WakeLock/MulticastLock, and calls `stopSelf()`.
- Control packet handling:
  - `PACKET_PING_CLIENT` / `PACKET_PING_HOST`: update `Room.members` and `lastSeenMs`; for client, `PACKET_PING_HOST` sets `Room.hostId`.
  - `PACKET_ROOM_DESTROYED`: if not host, notify `onRoomDestroyed()` and `leaveRoom()`.
  - `PACKET_CLIENT_LEAVE`: remove member.
  - `PACKET_MEMBER_JOINED` (5 bytes): add member.
  - `PACKET_MEMBER_LEFT` (5 bytes): remove member.
  - `PACKET_MEMBER_INFO` (variable): type + 4 bytes id + 1 byte name length + name bytes; update member username.
  - `PACKET_MUTE_MEMBER` (5 bytes): if received by the muted device, stop sending audio (set local muted state).
  - `PACKET_KICK_MEMBER` (5 bytes): if received by the kicked device, call `leaveRoom()`.
- Host authority:
  - Store the `hostId` for clients.
  - Clients should accept `MUTE`/`KICK`/`ROOM_DESTROYED` only when `senderId == room.hostId`.
  - Host can send `MUTE`/`KICK` to any member.
- Member list maintenance:
  - Schedule a `Runnable` every `Constants.MEMBER_CHECK_INTERVAL_MS` to remove members not seen for `Constants.MEMBER_TIMEOUT_MS` and notify `onMemberLeft`.
  - On audio activity, mark member as speaking and post a delayed reset after ~600 ms.

## 6. Minimal security
No encryption, no complex auth. Only basic sender-ID validation:
- Clients trust the first host they hear via `PACKET_PING_HOST`.
- Host commands (mute/kick/destroy) are accepted only from the known `hostId`.
- It is okay if a rogue device spoofs a host on the same network; do not add PKI.

## Build & deliver
1. `git checkout devin/refactor-base`
2. `git checkout -b devin/network-service`
3. Implement the changes above.
4. `gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug`.
5. Fix any compile errors.
6. `git add -A && git commit -m "network/service refactor: ..."`
7. `git push -u origin devin/network-service`
8. Report the branch name and build result.
