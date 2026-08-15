# VoxLink UI / Activity Integration Refactor Task

You are wiring the VoxLink Android UI to the new `VoxLinkService` and adding host controls.

## Repo & Branch
- Repository: `AmousaviNezhod/VoxLink`
- Start from the branch that already contains the network/service and audio child work (parent will tell you which branches to merge; use `git merge origin/devin/network-service` and `git merge origin/devin/audio-pipeline` into a new branch based on `devin/refactor-base`, e.g. `devin/ui-integration`).
- Run `gradlew.bat assembleDebug` and fix errors before pushing.
- Push `devin/ui-integration` and report the build result.

## Top-level goals
- Use `VoxLinkService` as the single owner of audio/network from the UI.
- Keep the UI fast and responsive; all heavy/audio/network work lives in the service.
- Add runtime permission handling that matches the new `AndroidManifest.xml`.
- Add host controls (mute/unmute, kick) for the manager in `VoiceRoomActivity`.

## Files you may edit
- `app/src/main/java/com/example/sample/MainActivity.java`
- `app/src/main/java/com/example/sample/VoiceRoomActivity.java`
- `app/src/main/res/layout/activity_main.xml` (only if needed)
- `app/src/main/res/layout/activity_voice_room.xml` (only if needed)
- `app/src/main/res/values/strings.xml` (add any new strings)
- `app/src/main/res/drawable/` (only if adding new icons; reuse existing drawables)

DO NOT modify `NetworkHelper.java`, `UdpSender.java`, `UdpReceiver.java`, `DiscoveryManager.java`, `VoxLinkService.java`, `AudioPlayer.java`, `AudioRecorder.java`, `AudioMixer.java`, `AudioEffects.java`, `Constants.java`, `Member.java`, `Room.java`, `AudioPacket.java`, or `AndroidManifest.xml`.

## 1. Permissions (MainActivity and VoiceRoomActivity)
The manifest declares these normal/dangerous permissions:
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
- `RECORD_AUDIO` (dangerous)
- `MODIFY_AUDIO_SETTINGS`
- `BLUETOOTH`, `BLUETOOTH_CONNECT` (dangerous on Android 12+)
- `POST_NOTIFICATIONS` (dangerous on Android 13+)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`

`MainActivity.checkAndRequestPermissions()` currently asks for `NEARBY_WIFI_DEVICES` or `ACCESS_FINE_LOCATION`. Remove those. Request only:
- `RECORD_AUDIO`
- `BLUETOOTH_CONNECT` (if `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`)
- `POST_NOTIFICATIONS` (if `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`)
- `MODIFY_AUDIO_SETTINGS`
- `CHANGE_WIFI_MULTICAST_STATE`

`VoiceRoomActivity` should also request `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` on entry if not granted. For `POST_NOTIFICATIONS`, if denied, still allow starting the service but use a `Toast` to tell the user that notifications are needed for the foreground service.

## 2. MainActivity changes
- Keep discovery and group creation flow, but the `goToVoiceRoom(role, hostAddress)` method should stay.
- In `startCreateGroup()`: do `discoveryManager.stopAll()` then start `VoiceRoomActivity` with `role="host"` and `hostAddress="127.0.0.1"` (the service will determine the real interface).
- In `startJoinGroup()`: start discovery. On `onGroupFound(hostAddress, port)`, start `VoiceRoomActivity` with `role="client"` and `hostAddress`.
- `onGroupLost`/`onError`: reset UI.

## 3. VoiceRoomActivity must bind to VoxLinkService
`VoiceRoomService` exposes a `LocalBinder` and `VoxLinkListener`. Make `VoiceRoomActivity` implement `VoxLinkService.VoxLinkListener` (or use an anonymous listener).

Lifecycle:
- In `onCreate`:
  - Read `role` and `hostAddress` extras.
  - Start `VoxLinkService` with `ContextCompat.startForegroundService(this, new Intent(this, VoxLinkService.class))`.
  - Bind to it with `BIND_AUTO_CREATE`.
- In `onServiceConnected`:
  - Get the service binder and call `service.startRoom(username, isHost, hostAddress)`.
  - Register the activity as `VoxLinkListener`.
- In `onDestroy`/`onPause` (or `handleLeaveOrDestroyRoom`):
  - If leaving: call `service.leaveRoom()` and `unbindService(this)`.
- The `AudioManager` communication-mode setup should be removed from the activity (it is now inside `AudioEffects` / `VoxLinkService`).

Remove direct references to `UdpSender`, `UdpReceiver`, `AudioRecorder`, `AudioPlayer`, and `AudioReceiveListener`/`AudioChunkListener` from `VoiceRoomActivity` (but keep imports only if the activity still implements them for backward compatibility; it should not). Use only `VoxLinkService` and its listener.

## 4. VoxLinkListener callbacks → UI updates
Use `VoxLinkListener` methods:
- `onMemberJoined(Member member)` → call `addMemberToUI(member.id, member.username, member.id == mySenderId, member.isHost)`.
- `onMemberLeft(int memberId)` → `removeMemberFromUI(memberId)`.
- `onMemberSpeaking(int memberId, boolean speaking)` → `updateMemberSpeaking(memberId, speaking)`.
- `onHostChanged(int hostId)` → update the member row that is now host with a crown icon/text; store `hostSenderId` locally.
- `onRoomDestroyed()` → show a `VoxToast` (or `Toast`) that the room was closed by the host and `finish()`.
- `onError(String message)` → show `VoxToast.error(this, message, 4000)`.

For `mySenderId`, expose `VoxLinkService.getMySenderId()` or get it from the first `onMemberJoined` event for the local member.

## 5. Host controls (mute / kick)
Add a way for the host to manage members:
- In `addMemberToUI`, when the local user is host and the row is **not** the local user, add a small button or make the row clickable to show a popup menu with two options:
  - "بی‌صدا کردن / Mute" (if muted, show "بازگرداندن صدا / Unmute")
  - "اخراج / Kick"
- Use `androidx.appcompat.widget.PopupMenu` or an `AlertDialog` with a list.
- When the host chooses mute/unmute, call `service.muteMember(memberId, muted)`.
- When the host chooses kick, show a confirmation dialog; if confirmed, call `service.kickMember(memberId)` and remove the row immediately from the UI.
- A muted member should be visually indicated (e.g. a muted icon or text like "🔇").
- The host row should still show the crown (`👑 مدیر`).

Host row interactions:
- The host should not be able to mute or kick themselves.
- Non-host users should not see mute/kick options; the row is not interactive.

## 6. PTT / always-on microphone
- The PTT and mic toggle buttons are already in the layout (`btnPTT`, `btnMicToggle`).
- In `setupPTTButton`:
  - `ACTION_DOWN` → call `service.setPttPressed(true)` and update UI.
  - `ACTION_UP`/`ACTION_CANCEL` → call `service.setPttPressed(false)` and update UI.
- In `setupMicToggle`:
  - When switching to always-on → call `service.toggleAlwaysOn()`; check `service.isAlwaysOn()` if exposed.
  - When switching back to PTT → call `service.toggleAlwaysOn()`.
- Visual updates (speaking dot, button text) can remain the same, but drive them through `onMemberSpeaking(mySenderId, ...)`.

## 7. Leave / destroy room
- In `handleLeaveOrDestroyRoom`, call `service.leaveRoom()`.
- Do not manually send UDP packets from the activity.
- After calling `leaveRoom`, `unbindService` and `finish()`.

## 8. Member row UI
- Keep the existing dynamically-created `LinearLayout` row style (dot, name, role).
- If the member is muted, append a `🔇` or a separate `TextView` with text `"بی‌صدا"` and color `@color/text_hint`.
- If the member is the host, keep `👑 مدیر` in gold (`0xFFFBBF24`).
- If the member is the local user, keep the `(تو)` suffix and maybe do not show mute/kick menu.

## 9. Strings
Add or reuse Persian strings in `res/values/strings.xml`:
- `mic_mode`
- `push_to_talk`
- `always_on`
- `hold_to_speak`
- `speaking`
- `you`
- `user`
- `host`
- `member_count` (plurals if you want)
- `mute`
- `unmute`
- `kick`
- `kick_confirm_title`
- `kick_confirm_message`
- `yes`
- `cancel`

Do not delete existing strings.

## 10. Build & deliver
1. `git checkout devin/refactor-base`
2. `git checkout -b devin/ui-integration`
3. `git merge origin/devin/network-service` (or the exact branch the parent tells you)
4. `git merge origin/devin/audio-pipeline` (or the exact branch the parent tells you)
5. Resolve any merge conflicts (if `VoiceRoomActivity` has incompatible changes, take the service-based version).
6. Implement the UI wiring above.
7. `gradlew.bat assembleDebug`
8. Fix compile errors.
9. `git add -A && git commit -m "ui integration: bind VoxLinkService, host controls, permissions"`
10. `git push -u origin devin/ui-integration`
11. Report branch and build status.
