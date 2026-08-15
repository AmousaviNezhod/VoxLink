package com.example.sample.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.sample.R;
import com.example.sample.audio.AudioEffects;
import com.example.sample.audio.AudioMixer;
import com.example.sample.audio.AudioPlayer;
import com.example.sample.audio.AudioRecorder;
import com.example.sample.model.Member;
import com.example.sample.model.Room;
import com.example.sample.network.DiscoveryManager;
import com.example.sample.network.NetworkHelper;
import com.example.sample.network.UdpReceiver;
import com.example.sample.network.UdpSender;
import com.example.sample.util.Constants;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoxLinkService extends Service {
    private static final String TAG = "VoxLinkService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "voxlink_voice_channel";

    private final IBinder binder = new LocalBinder();
    private final AtomicBoolean roomActive = new AtomicBoolean(false);
    private final AtomicBoolean cleaningUp = new AtomicBoolean(false);

    private Handler mainHandler;
    private HandlerThread handlerThread;
    private Handler serviceHandler;

    private UdpSender udpSender;
    private UdpReceiver udpReceiver;
    private AudioPlayer audioPlayer;
    private AudioMixer audioMixer;
    private AudioRecorder audioRecorder;
    private DiscoveryManager discoveryManager;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;

    private Room room;
    private String username;
    private boolean isHost;
    private String hostAddress;
    private int mySenderId = -1;

    private volatile boolean isPTTPressed;
    private volatile boolean isAlwaysOn;
    private volatile boolean isLocalMuted;

    private VoxLinkListener listener;
    private final Set<Integer> knownMemberIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastSpeakingTime = new ConcurrentHashMap<>();
    private final Map<Integer, Runnable> speakingResetRunnables = new ConcurrentHashMap<>();

    private Runnable pingRunnable;
    private Runnable memberCheckRunnable;

    public interface VoxLinkListener {
        void onMemberJoined(Member member);
        void onMemberLeft(int memberId);
        void onMemberSpeaking(int memberId, boolean speaking);
        void onHostChanged(int hostId);
        void onRoomDestroyed();
        void onError(String message);
    }

    public class LocalBinder extends Binder {
        public void startRoom(String username, boolean isHost, String hostAddress) {
            VoxLinkService.this.startRoom(username, isHost, hostAddress);
        }

        public void leaveRoom() {
            VoxLinkService.this.leaveRoom();
        }

        public void setPttPressed(boolean pressed) {
            VoxLinkService.this.setPttPressed(pressed);
        }

        public void toggleAlwaysOn() {
            VoxLinkService.this.toggleAlwaysOn();
        }

        public void muteMember(int memberId, boolean muted) {
            VoxLinkService.this.muteMember(memberId, muted);
        }

        public void kickMember(int memberId) {
            VoxLinkService.this.kickMember(memberId);
        }

        public Room getRoom() {
            return VoxLinkService.this.getRoom();
        }

        public void setListener(VoxLinkListener listener) {
            VoxLinkService.this.setListener(listener);
        }

        public int getMySenderId() {
            return VoxLinkService.this.mySenderId;
        }

        public boolean isHost() {
            return VoxLinkService.this.isHost;
        }

        public boolean isAlwaysOn() {
            return VoxLinkService.this.isAlwaysOn;
        }

        public boolean isLocalMuted() {
            return VoxLinkService.this.isLocalMuted;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        handlerThread = new HandlerThread("VoxLinkServiceThread", Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        serviceHandler = new Handler(handlerThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (roomActive.compareAndSet(true, false)) {
            doCleanup();
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void setListener(VoxLinkListener listener) {
        this.listener = listener;
    }

    private Room getRoom() {
        return room;
    }

    private void startRoom(String username, boolean isHost, String hostAddress) {
        if (!roomActive.compareAndSet(false, true)) {
            Log.w(TAG, "Room already active");
            return;
        }
        this.username = (username == null || username.isEmpty()) ? "User" : username;
        this.isHost = isHost;
        this.hostAddress = hostAddress;

        try {
            startForegroundWithNotification();
            acquireLocks();

            udpSender = new UdpSender(this);
            if (!udpSender.prepare()) {
                throw new RuntimeException("UdpSender prepare failed");
            }
            mySenderId = udpSender.getSenderId();

            room = new Room(UUID.randomUUID().toString(), isHost);
            room.mySenderId = mySenderId;
            room.hostId = isHost ? mySenderId : -1;
            Member self = room.getOrCreate(mySenderId, this.username);
            self.isHost = isHost;
            self.ipAddress = getLocalIpAddress();
            knownMemberIds.add(mySenderId);

            audioPlayer = new AudioPlayer();
            if (!audioPlayer.prepare()) {
                throw new RuntimeException("AudioPlayer prepare failed");
            }
            audioMixer = new AudioMixer(audioPlayer, Constants.FRAME_SIZE);
            audioMixer.start();

            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            AudioEffects.configureForCommunication(this, audioManager, true);

            udpReceiver = new UdpReceiver(this);
            udpReceiver.setAudioPacketListener((senderId, sequence, payload) -> {
                if (audioMixer != null) {
                    audioMixer.enqueue(senderId, sequence, payload);
                }
                updateSpeaking(senderId);
            });
            udpReceiver.setControlPacketListener((senderId, payload) -> handleControlPacket(senderId, payload));
            if (!udpReceiver.start(mySenderId)) {
                throw new RuntimeException("UdpReceiver start failed");
            }

            audioRecorder = new AudioRecorder(chunk -> {
                if (udpSender != null && udpSender.isReady() && !isLocalMuted) {
                    udpSender.sendAudio(chunk);
                }
            });
            if (!audioRecorder.prepare()) {
                throw new RuntimeException("AudioRecorder prepare failed");
            }

            discoveryManager = new DiscoveryManager(this, new DiscoveryManager.DiscoveryListener() {
                @Override public void onGroupFound(String hostAddress, int port) {}
                @Override public void onGroupLost() { notifyRoomDestroyed(); }
                @Override public void onError(String message) { notifyError(message); }
            });

            if (isHost) {
                discoveryManager.registerGroup();
            } else if (hostAddress == null || hostAddress.isEmpty()) {
                discoveryManager.startDiscovery();
            }

            serviceHandler.postDelayed(this::sendMemberInfo, 300);
            startPingLoop();
            startMemberCheckLoop();

            if (listener != null) {
                final Member selfRef = self;
                mainHandler.post(() -> {
                    if (isHost) listener.onHostChanged(mySenderId);
                    listener.onMemberJoined(selfRef);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "startRoom failed: " + e.getMessage());
            notifyError(e.getMessage());
            leaveRoom();
        }
    }

    private String getLocalIpAddress() {
        InetAddress addr = NetworkHelper.getLocalInetAddress(this);
        return addr != null ? addr.getHostAddress() : null;
    }

    private void leaveRoom() {
        if (!roomActive.compareAndSet(true, false)) return;
        stopLoops();
        if (serviceHandler != null) {
            serviceHandler.post(this::sendLeaveAndCleanup);
        } else {
            sendLeaveAndCleanup();
        }
    }

    private void stopLoops() {
        if (serviceHandler != null) {
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler != null) {
            for (Runnable r : speakingResetRunnables.values()) {
                mainHandler.removeCallbacks(r);
            }
            speakingResetRunnables.clear();
        }
    }

    private void sendLeaveAndCleanup() {
        if (udpSender != null && udpSender.isReady()) {
            byte[] packet = isHost
                    ? new byte[]{Constants.PACKET_ROOM_DESTROYED}
                    : new byte[]{Constants.PACKET_CLIENT_LEAVE};
            int count = isHost ? 5 : 3;
            int delay = isHost ? 50 : 30;
            for (int i = 0; i < count; i++) {
                udpSender.sendControl(packet);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {}
            }
        }
        doCleanup();
    }

    private void doCleanup() {
        if (!cleaningUp.compareAndSet(false, true)) return;
        if (audioRecorder != null) audioRecorder.release();
        if (audioMixer != null) audioMixer.stop();
        if (audioPlayer != null) audioPlayer.stop();
        if (audioManager != null) {
            AudioEffects.configureForCommunication(this, audioManager, false);
        }
        if (udpReceiver != null) udpReceiver.stop();
        if (udpSender != null) udpSender.close();
        if (discoveryManager != null) discoveryManager.stopAll();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
    }

    private void setPttPressed(boolean pressed) {
        isPTTPressed = pressed;
        updateRecordingState();
    }

    private void toggleAlwaysOn() {
        isAlwaysOn = !isAlwaysOn;
        updateRecordingState();
    }

    private void updateRecordingState() {
        boolean want = (isPTTPressed || isAlwaysOn) && !isLocalMuted;
        if (audioRecorder == null) return;
        if (want) {
            if (!audioRecorder.isRecording()) {
                audioRecorder.startRecording();
                updateSelfSpeaking(true);
            }
        } else {
            if (audioRecorder.isRecording()) {
                audioRecorder.stopRecording();
                updateSelfSpeaking(false);
            }
        }
    }

    private void updateSelfSpeaking(boolean speaking) {
        if (room != null) {
            Member self = room.members.get(mySenderId);
            if (self != null) self.isSpeaking.set(speaking);
        }
        if (listener != null) {
            final boolean speak = speaking;
            mainHandler.post(() -> { if (listener != null) listener.onMemberSpeaking(mySenderId, speak); });
        }
    }

    private void updateSpeaking(int senderId) {
        if (room == null || senderId == mySenderId) return;
        final Member member = room.getOrCreate(senderId, "User " + (senderId % 1000));
        member.isSpeaking.set(true);
        final long now = System.currentTimeMillis();
        lastSpeakingTime.put(senderId, now);

        mainHandler.post(() -> { if (listener != null) listener.onMemberSpeaking(senderId, true); });

        Runnable old = speakingResetRunnables.get(senderId);
        if (old != null) mainHandler.removeCallbacks(old);
        Runnable reset = () -> {
            speakingResetRunnables.remove(senderId);
            Long last = lastSpeakingTime.get(senderId);
            if (last != null && System.currentTimeMillis() - last >= 500) {
                member.isSpeaking.set(false);
                if (listener != null) listener.onMemberSpeaking(senderId, false);
            }
        };
        speakingResetRunnables.put(senderId, reset);
        mainHandler.postDelayed(reset, 600);
    }

    private void muteMember(int memberId, boolean muted) {
        if (memberId == mySenderId) {
            setLocalMuted(muted);
        }
        if (!isHost) return;
        Member m = room != null ? room.members.get(memberId) : null;
        if (m != null) m.isMuted.set(muted);
        if (muted && udpSender != null && udpSender.isReady()) {
            byte[] payload = new byte[Constants.MEMBER_CONTROL_PACKET_SIZE];
            payload[0] = Constants.PACKET_MUTE_MEMBER;
            writeInt(payload, 1, memberId);
            udpSender.sendControl(payload);
        }
    }

    private void setLocalMuted(boolean muted) {
        isLocalMuted = muted;
        if (audioMixer != null) audioMixer.setMuted(muted);
        updateRecordingState();
    }

    private void kickMember(int memberId) {
        if (!isHost) return;
        if (memberId == mySenderId) {
            leaveRoom();
            return;
        }
        removeMember(memberId);
        if (udpSender != null && udpSender.isReady()) {
            byte[] payload = new byte[Constants.MEMBER_CONTROL_PACKET_SIZE];
            payload[0] = Constants.PACKET_KICK_MEMBER;
            writeInt(payload, 1, memberId);
            udpSender.sendControl(payload);
        }
    }

    private void handleControlPacket(int senderId, byte[] payload) {
        if (payload == null || payload.length < 1 || room == null) return;
        byte type = payload[0];
        long now = System.currentTimeMillis();

        switch (type) {
            case Constants.PACKET_PING_HOST:
                handlePingHost(senderId, now);
                break;
            case Constants.PACKET_PING_CLIENT:
                handlePingClient(senderId, now);
                break;
            case Constants.PACKET_ROOM_DESTROYED:
                if (!isHost && isFromHost(senderId)) {
                    notifyRoomDestroyed();
                    leaveRoom();
                }
                break;
            case Constants.PACKET_CLIENT_LEAVE:
                removeMember(senderId);
                break;
            case Constants.PACKET_MEMBER_JOINED:
                if (payload.length >= 5) {
                    int id = readInt(payload, 1);
                    addMember(id, false, "User " + (id % 1000));
                }
                break;
            case Constants.PACKET_MEMBER_LEFT:
                if (payload.length >= 5) {
                    int id = readInt(payload, 1);
                    removeMember(id);
                }
                break;
            case Constants.PACKET_MEMBER_INFO:
                if (payload.length >= 6) {
                    int id = readInt(payload, 1);
                    int nameLen = payload[5] & 0xFF;
                    if (payload.length >= 6 + nameLen) {
                        String name = new String(payload, 6, nameLen, StandardCharsets.UTF_8);
                        updateMemberName(id, name);
                    }
                }
                break;
            case Constants.PACKET_MUTE_MEMBER:
                if (payload.length >= 5) {
                    int id = readInt(payload, 1);
                    handleMuteMember(senderId, id);
                }
                break;
            case Constants.PACKET_KICK_MEMBER:
                if (payload.length >= 5) {
                    int id = readInt(payload, 1);
                    handleKickMember(senderId, id);
                }
                break;
            default:
                break;
        }
    }

    private void handlePingHost(int senderId, long now) {
        if (isHost) return;
        if (room.hostId <= 0) {
            room.hostId = senderId;
            notifyHostChanged(senderId);
        }
        if (room.hostId == senderId) {
            Member m = room.getOrCreate(senderId, "Host " + (senderId % 1000));
            m.isHost = true;
            m.lastSeenMs = now;
            notifyMemberAddedIfNew(m);
        }
    }

    private void handlePingClient(int senderId, long now) {
        Member m = room.getOrCreate(senderId, "User " + (senderId % 1000));
        m.lastSeenMs = now;
        if (isHost) {
            if (notifyMemberAddedIfNew(m)) {
                sendMemberJoined(senderId);
            }
        } else {
            notifyMemberAddedIfNew(m);
        }
    }

    private void addMember(int id, boolean isHostFlag, String defaultName) {
        if (id == mySenderId) return;
        Member m = room.getOrCreate(id, defaultName);
        m.isHost = isHostFlag;
        notifyMemberAddedIfNew(m);
    }

    private void updateMemberName(int id, String name) {
        if (id == mySenderId) return;
        Member m = room.getOrCreate(id, name);
        m.username = name;
        m.lastSeenMs = System.currentTimeMillis();
        notifyMemberAddedIfNew(m);
    }

    private boolean notifyMemberAddedIfNew(Member m) {
        if (knownMemberIds.add(m.id)) {
            if (m.id != mySenderId && listener != null) {
                final Member member = m;
                mainHandler.post(() -> { if (listener != null) listener.onMemberJoined(member); });
            }
            return true;
        }
        return false;
    }

    private void removeMember(int id) {
        if (id == mySenderId || room == null) return;
        Member removed = room.members.remove(id);
        if (removed != null) {
            knownMemberIds.remove(id);
            mainHandler.post(() -> { if (listener != null) listener.onMemberLeft(id); });
            if (isHost && udpSender != null && udpSender.isReady()) {
                sendMemberLeft(id);
            }
        }
    }

    private void handleMuteMember(int senderId, int targetId) {
        if (targetId == mySenderId) {
            if (isHost && senderId != mySenderId) return;
            if (!isHost && room.hostId > 0 && senderId != room.hostId) return;
            setLocalMuted(true);
        } else if (isHost) {
            Member m = room.members.get(targetId);
            if (m != null) m.isMuted.set(true);
        }
    }

    private void handleKickMember(int senderId, int targetId) {
        if (targetId == mySenderId) {
            if (isHost && senderId != mySenderId) return;
            if (!isHost && room.hostId > 0 && senderId != room.hostId) return;
            leaveRoom();
        } else if (isHost && senderId == mySenderId) {
            removeMember(targetId);
        }
    }

    private boolean isFromHost(int senderId) {
        return room.hostId == -1 || room.hostId == senderId;
    }

    private void startPingLoop() {
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!roomActive.get() || udpSender == null || !udpSender.isReady()) return;
                byte[] ping = new byte[] { isHost ? Constants.PACKET_PING_HOST : Constants.PACKET_PING_CLIENT };
                udpSender.sendControl(ping);
                serviceHandler.postDelayed(this, Constants.PING_INTERVAL_MS);
            }
        };
        serviceHandler.postDelayed(pingRunnable, Constants.PING_INTERVAL_MS);
    }

    private void startMemberCheckLoop() {
        memberCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!roomActive.get() || room == null) return;
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<Integer, Member>> it = room.members.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Member> entry = it.next();
                    Member m = entry.getValue();
                    if (m.id == mySenderId) continue;
                    if (now - m.lastSeenMs > Constants.MEMBER_TIMEOUT_MS) {
                        final int id = m.id;
                        it.remove();
                        knownMemberIds.remove(id);
                        mainHandler.post(() -> { if (listener != null) listener.onMemberLeft(id); });
                        if (isHost && udpSender != null && udpSender.isReady()) {
                            sendMemberLeft(id);
                        }
                        if (!isHost && id == room.hostId) {
                            notifyRoomDestroyed();
                            leaveRoom();
                            return;
                        }
                    }
                }
                serviceHandler.postDelayed(this, Constants.MEMBER_CHECK_INTERVAL_MS);
            }
        };
        serviceHandler.postDelayed(memberCheckRunnable, Constants.MEMBER_CHECK_INTERVAL_MS);
    }

    private void sendMemberInfo() {
        if (udpSender == null || !udpSender.isReady() || username == null) return;
        byte[] nameBytes = username.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(nameBytes.length, 255);
        byte[] payload = new byte[1 + 4 + 1 + len];
        payload[0] = Constants.PACKET_MEMBER_INFO;
        writeInt(payload, 1, mySenderId);
        payload[5] = (byte) len;
        System.arraycopy(nameBytes, 0, payload, 6, len);
        udpSender.sendControl(payload);
    }

    private void sendMemberJoined(int id) {
        if (udpSender == null || !udpSender.isReady()) return;
        byte[] payload = new byte[Constants.MEMBER_CONTROL_PACKET_SIZE];
        payload[0] = Constants.PACKET_MEMBER_JOINED;
        writeInt(payload, 1, id);
        udpSender.sendControl(payload);
    }

    private void sendMemberLeft(int id) {
        if (udpSender == null || !udpSender.isReady()) return;
        byte[] payload = new byte[Constants.MEMBER_CONTROL_PACKET_SIZE];
        payload[0] = Constants.PACKET_MEMBER_LEFT;
        writeInt(payload, 1, id);
        udpSender.sendControl(payload);
    }

    private void notifyHostChanged(int hostId) {
        if (listener != null) {
            mainHandler.post(() -> { if (listener != null) listener.onHostChanged(hostId); });
        }
    }

    private void notifyRoomDestroyed() {
        if (listener != null) {
            mainHandler.post(() -> { if (listener != null) listener.onRoomDestroyed(); });
        }
    }

    private void notifyError(String message) {
        if (listener != null) {
            mainHandler.post(() -> { if (listener != null) listener.onError(message); });
        }
    }

    private void startForegroundWithNotification() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoxLink")
                .setContentText("Voice room active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "VoxLink", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Active voice room notifications");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void acquireLocks() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("VoxLinkMulticastLock");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxLink:WakeLock");
            wakeLock.acquire();
        }
    }

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24) |
                ((buffer[offset + 1] & 0xFF) << 16) |
                ((buffer[offset + 2] & 0xFF) << 8) |
                (buffer[offset + 3] & 0xFF);
    }
}
