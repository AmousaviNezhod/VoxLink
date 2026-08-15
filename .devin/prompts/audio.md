# VoxLink Audio Pipeline Refactor Task

You are implementing the audio layer of an Android intercom/walkie-talkie app.

## Repo & Branch
- Repository: `AmousaviNezhod/VoxLink`
- Start from branch: `devin/refactor-base`
- Create a new branch from it, e.g. `devin/audio-pipeline`
- Push your final work to that branch and run `gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug` (Linux/macOS) before pushing. Report the branch name and any build errors.

## Top-level goals (parent direction)
- Priority 1: **low latency, echo/noise cancellation, jitter robustness**.
- Priority 2: **device compatibility** (headset/Bluetooth, different Android versions).
- Do not change UI, network, or service files.

## Files you may edit
- `app/src/main/java/com/example/sample/audio/AudioPlayer.java`
- `app/src/main/java/com/example/sample/audio/AudioRecorder.java`
- `app/src/main/java/com/example/sample/audio/AudioMixer.java`
- `app/src/main/java/com/example/sample/audio/AudioEffects.java`
- `app/src/main/java/com/example/sample/util/Constants.java` (only to add missing audio-related constants if needed)

DO NOT modify `MainActivity.java`, `VoiceRoomActivity.java`, `NetworkHelper.java`, `UdpSender.java`, `UdpReceiver.java`, `DiscoveryManager.java`, `VoxLinkService.java`, or any UI resource files.

## Current state
- `Constants.java` already has: `SAMPLE_RATE=16000`, `FRAME_SIZE=320` (samples = 640 bytes), `FRAME_MS=20`, `PACKET_HEADER_SIZE=8`, `MAX_PACKET_SIZE=1024`.
- `AudioPlayer.java` and `AudioRecorder.java` are skeletons in `com.example.sample.audio`.
- `AudioMixer.java` and `AudioEffects.java` are skeletons with a defined public API.

Read every audio file and `Constants.java` before editing.

## 1. AudioPlayer
- Build an `AudioTrack` with:
  - `AudioAttributes.USAGE_VOICE_COMMUNICATION`
  - `AudioAttributes.CONTENT_TYPE_SPEECH`
  - `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY`
- Buffer size: `AudioTrack.getMinBufferSize(Constants.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)`.
- Public methods:
  - `boolean prepare()`
  - `void play(byte[] pcm)` — checks `AudioTrack.write()` return; logs or retries on partial write
  - `void writeShorts(short[] samples)` — converts little-endian to bytes and calls `play()`
  - `void stop()`
  - `boolean isReady()`

## 2. AudioRecorder
- Build an `AudioRecord` with:
  - `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  - `Constants.SAMPLE_RATE`
  - `AudioFormat.CHANNEL_IN_MONO`
  - `AudioFormat.ENCODING_PCM_16BIT`
  - Buffer size = `2 * AudioRecord.getMinBufferSize(...)` or the minimum.
- In the record thread, call `Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)`.
- Read raw PCM and deliver **exactly one frame** (`Constants.FRAME_SIZE * 2` bytes) at a time to `AudioChunkListener.onChunkReady(byte[])`.
- If `read()` returns more or less than one frame, buffer bytes until a full frame is available before calling the listener.
- Public methods:
  - `boolean prepare()` — attach `AudioEffects` using `audioRecord.getAudioSessionId()`.
  - `void startRecording()`
  - `void stopRecording()`
  - `void release()`
  - `boolean isRecording()`
  - `void setAudioChunkListener(AudioChunkListener listener)`
- Keep `AudioChunkListener` nested interface as is:
  - `interface AudioChunkListener { void onChunkReady(byte[] audioChunk); }`

## 3. AudioMixer
Implement per-sender jitter buffer + mixer + low-latency playback thread. The public API is already declared:
- `AudioMixer(AudioPlayer audioPlayer, int frameSize)`
- `void start()`
- `void stop()`
- `void setMuted(boolean muted)`
- `void enqueue(int senderId, int sequence, byte[] pcm)`

Behavior:
- Each `senderId` gets its own `JitterBuffer`.
- Decode each payload to a `short[]` of length `frameSize` (little-endian, 16-bit).
- JitterBuffer per sender:
  - Store frames in a `TreeMap<Integer, short[]>` keyed by `sequence`.
  - Start with a prefetch target of 3 frames (60 ms). When 3 frames are buffered, set `nextPlaySeq = first sequence`.
  - `getFrame()` returns `buffer.remove(nextPlaySeq++)` if present; otherwise returns `null` (will be silence).
  - If the buffer is empty, reset the prefetch state and wait again.
  - Drop packets older than `nextPlaySeq` or newer than `nextPlaySeq + 20`.
  - Remove a JitterBuffer if no packet has arrived for 2 seconds.
- Playback thread:
  - Run every `Constants.FRAME_MS` milliseconds.
  - For every active sender, get a `short[]` frame (or silence/zeros if null).
  - Sum all frames sample-wise and clamp to `Short.MIN_VALUE..Short.MAX_VALUE`.
  - Convert the mixed `short[]` to bytes and call `audioPlayer.play(bytes)`.
  - If no active senders, optionally write a silence frame of `frameSize * 2` bytes to keep `AudioTrack` active.
- `setMuted(boolean)` should silence local playback while muted.
- Thread safety: use `ConcurrentHashMap` and `synchronized` on individual `JitterBuffer`s.

## 4. AudioEffects
Implement AEC/NS/AGC and basic audio routing.
- Public static methods:
  - `boolean isAecAvailable()`
  - `boolean isNsAvailable()`
  - `boolean isAgcAvailable()`
  - `void attach(AudioRecord audioRecord)` — create `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` for `audioRecord.getAudioSessionId()` if available and `isAvailable()`.
  - `void configureForCommunication(Context context, AudioManager audioManager, boolean active)` — when active, set `audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)` and `audioManager.setSpeakerphoneOn(true)`. When inactive, reset to `MODE_NORMAL` and `setSpeakerphoneOn(false)`.
  - `void release()` — release created audio effects.
- Optional (if time allows): add `AudioDeviceCallback` to route to wired headset or Bluetooth SCO. Expose `startBluetoothSco()` / `stopBluetoothSco()` through `AudioEffects` if you implement it.

## Build & deliver
1. `git checkout devin/refactor-base`
2. `git checkout -b devin/audio-pipeline`
3. Implement the changes above.
4. `gradlew.bat assembleDebug`.
5. Fix any compile errors.
6. `git add -A && git commit -m "audio pipeline refactor: ..."`
7. `git push -u origin devin/audio-pipeline`
8. Report the branch name and build result.
