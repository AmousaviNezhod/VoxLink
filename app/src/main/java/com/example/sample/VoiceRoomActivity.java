package com.example.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceRoomActivity extends AppCompatActivity implements UdpReceiver.AudioReceiveListener, AudioRecorder.AudioChunkListener {

    private static final int CONTROL_PACKET_SIZE = 1;
    private static final int MEMBER_CONTROL_PACKET_SIZE = 5;

    private TextView btnPTT, tvPttHint, tvRoomSubtitle, btnMicToggle, tvMemberCount;
    private LinearLayout membersContainer;
    private View pttOuter, pttRipple2, pttRipple3;

    private UdpSender udpSender;
    private UdpReceiver udpReceiver;
    private AudioRecorder audioRecorder;
    private AudioPlayer audioPlayer;

    private boolean isPTTMode = true;
    private boolean isAlwaysOnActive = false;
    private boolean isHost = false;
    private boolean isPttPulsing = false;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler presenceHandler = new Handler(Looper.getMainLooper());

    private Map<Integer, Long> membersMap = new ConcurrentHashMap<>();
    private Set<Integer> knownRemoteMembers = ConcurrentHashMap.newKeySet();

    private volatile int mySenderId = -1;
    private volatile int hostSenderId = -1;
    private String username;

    private Runnable memberCheckRunnable;
    private Runnable pttPulseRunnable;
    private Runnable presenceRunnable;

    private static final byte PACKET_PING_CLIENT = 0x01;
    private static final byte PACKET_PING_HOST = 0x02;
    private static final byte PACKET_ROOM_DESTROYED = 0x03;
    private static final byte PACKET_CLIENT_LEAVE = 0x04;
    private static final byte PACKET_MEMBER_JOINED = 0x05;
    private static final byte PACKET_MEMBER_LEFT = 0x06;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_room);
        findViews();

        SharedPreferences prefs = getSharedPreferences("voxlink", MODE_PRIVATE);
        username = prefs.getString("username", "User");

        String role = getIntent().getStringExtra("role");
        String hostAddress = getIntent().getStringExtra("hostAddress");
        isHost = "host".equals(role);

        if (isHost) {
            tvRoomSubtitle.setText(getString(R.string.you_are_host));
        } else {
            tvRoomSubtitle.setText(getString(R.string.connected_to, hostAddress));
        }

        initAudioAndNetwork();
        setupMicToggle();
        setupPTTButton();
        setupLeaveButton();
        startMemberChecker();
        startPresencePinger();
        playEnterAnimation();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleLeaveOrDestroyRoom();
            }
        });
    }

    private void findViews() {
        btnPTT = findViewById(R.id.btnPTT);
        tvPttHint = findViewById(R.id.tvPttHint);
        tvRoomSubtitle = findViewById(R.id.tvRoomSubtitle);
        btnMicToggle = findViewById(R.id.btnMicToggle);
        membersContainer = findViewById(R.id.membersContainer);
        tvMemberCount = findViewById(R.id.tvMemberCount);
        pttOuter = findViewById(R.id.pttOuter);
        pttRipple2 = findViewById(R.id.pttRipple2);
        pttRipple3 = findViewById(R.id.pttRipple3);
    }

    private void initAudioAndNetwork() {
        audioPlayer = new AudioPlayer();
        if (!audioPlayer.prepare()) {
            showInitErrorAndExit(getString(R.string.error_audio_player));
            return;
        }

        // ✅ تنظیم حیاتی برای پخش از Speaker اصلی
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }

        audioRecorder = new AudioRecorder(this);
        if (!audioRecorder.prepare()) {
            showInitErrorAndExit(getString(R.string.error_audio_recorder));
            return;
        }

        udpSender = new UdpSender();
        new Thread(() -> {
            if (udpSender.prepare()) {
                mySenderId = udpSender.getSenderId();
                knownRemoteMembers.add(mySenderId);
                mainHandler.post(() -> addMemberToUI(mySenderId, username, true, isHost));

                udpReceiver = new UdpReceiver(this, this);
                if (!udpReceiver.start(mySenderId)) {
                    mainHandler.post(() -> showInitErrorAndExit(getString(R.string.error_network_receiver)));
                }
            } else {
                mainHandler.post(() -> showInitErrorAndExit(getString(R.string.error_network_sender)));
            }
        }).start();
    }

    private void showInitErrorAndExit(String message) {
        VoxToast.error(this, message, 4500);
        mainHandler.postDelayed(() -> {
            cleanup();
            finish();
        }, 2000);
    }

    private void startPresencePinger() {
        presenceRunnable = new Runnable() {
            @Override
            public void run() {
                if (udpSender != null && udpSender.isReady() && mySenderId > 0) {
                    new Thread(() -> {
                        byte[] pingPacket = new byte[]{isHost ? PACKET_PING_HOST : PACKET_PING_CLIENT};
                        udpSender.send(pingPacket);
                    }).start();
                }
                presenceHandler.postDelayed(this, 2000);
            }
        };
        presenceHandler.postDelayed(presenceRunnable, 1000);
    }

    private void startMemberChecker() {
        memberCheckRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                boolean hostTimedOut = false;
                java.util.Iterator<Map.Entry<Integer, Long>> iter = membersMap.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<Integer, Long> entry = iter.next();
                    int senderId = entry.getKey();
                    long lastSeen = entry.getValue();
                    if (now - lastSeen > Constants.MEMBER_TIMEOUT_MS) {
                        if (!isHost && senderId == hostSenderId) {
                            hostTimedOut = true;
                        }
                        iter.remove();
                        knownRemoteMembers.remove(senderId);
                        int sid = senderId;
                        mainHandler.post(() -> removeMemberFromUI(sid));
                        if (isHost) {
                            broadcastMemberLeft(sid);
                        }
                    } else if (now - lastSeen > 1200) {
                        int sid = senderId;
                        mainHandler.post(() -> updateMemberSpeaking(sid, false));
                    }
                }
                if (hostTimedOut) {
                    mainHandler.post(() -> {
                        VoxToast.error(VoiceRoomActivity.this, "مدیر اتاق قطع اتصال شد.", 4000);
                        cleanup();
                        finish();
                    });
                }
                mainHandler.postDelayed(this, Constants.MEMBER_CHECK_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(memberCheckRunnable, Constants.MEMBER_CHECK_INTERVAL_MS);
    }

    public void addMemberToUI(int senderId, String name, boolean isMe, boolean isAdmin) {
        for (int i = 0; i < membersContainer.getChildCount(); i++) {
            View child = membersContainer.getChildAt(i);
            if (child.getTag() != null && child.getTag().equals(senderId)) {
                if (child instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) child;
                    if (row.getChildCount() >= 3 && !isMe) {
                        TextView tvRole = (TextView) row.getChildAt(2);
                        if (isAdmin && !tvRole.getText().toString().contains("مدیر")) {
                            tvRole.setText("👑 مدیر");
                            tvRole.setTextColor(0xFFFBBF24);
                        }
                    }
                }
                return;
            }
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 8;
        row.setLayoutParams(params);
        row.setBackgroundResource(R.drawable.bg_member_row);
        row.setPadding(16, 12, 16, 12);

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(18, 18);
        dotParams.rightMargin = 12;
        dot.setLayoutParams(dotParams);
        dot.setBackgroundResource(R.drawable.bg_online_dot);

        TextView tvName = new TextView(this);
        tvName.setText(isMe ? name + " (" + getString(R.string.you) + ")" : name);
        tvName.setTextColor(getColorRes(R.color.text_primary));
        tvName.setTextSize(13);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvRole = new TextView(this);
        if (isAdmin) {
            tvRole.setText("👑 مدیر");
            tvRole.setTextColor(0xFFFBBF24);
        } else {
            tvRole.setText("👤 کاربر");
            tvRole.setTextColor(0xFF94A3B8);
        }
        tvRole.setTextSize(12);

        row.addView(dot);
        row.addView(tvName);
        row.addView(tvRole);
        row.setTag(senderId);
        dot.setTag("dot_" + senderId);
        membersContainer.addView(row);

        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(300);
        row.startAnimation(fadeIn);
        updateMemberCount();
    }

    public void removeMemberFromUI(int senderId) {
        for (int i = 0; i < membersContainer.getChildCount(); i++) {
            View child = membersContainer.getChildAt(i);
            if (child.getTag() != null && child.getTag().equals(senderId)) {
                AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
                fadeOut.setDuration(300);
                View rowToRemove = child;
                fadeOut.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation animation) {}
                    @Override public void onAnimationEnd(Animation animation) {
                        membersContainer.removeView(rowToRemove);
                        updateMemberCount();
                    }
                    @Override public void onAnimationRepeat(Animation animation) {}
                });
                child.startAnimation(fadeOut);
                break;
            }
        }
    }

    private void updateMemberCount() {
        int count = membersContainer.getChildCount();
        tvMemberCount.setText(getString(R.string.member_count, count));
    }

    private void broadcastMemberJoined(int newMemberId) {
        if (udpSender == null || !udpSender.isReady()) return;
        new Thread(() -> {
            byte[] packet = new byte[MEMBER_CONTROL_PACKET_SIZE];
            packet[0] = PACKET_MEMBER_JOINED;
            writeIntToBytes(packet, 1, newMemberId);
            for (int i = 0; i < 3; i++) {
                udpSender.send(packet);
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void broadcastMemberLeft(int leftMemberId) {
        if (udpSender == null || !udpSender.isReady()) return;
        new Thread(() -> {
            byte[] packet = new byte[MEMBER_CONTROL_PACKET_SIZE];
            packet[0] = PACKET_MEMBER_LEFT;
            writeIntToBytes(packet, 1, leftMemberId);
            for (int i = 0; i < 3; i++) {
                udpSender.send(packet);
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private static void writeIntToBytes(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) value;
    }

    private static int readIntFromBytes(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24) |
                ((buffer[offset + 1] & 0xFF) << 16) |
                ((buffer[offset + 2] & 0xFF) << 8) |
                (buffer[offset + 3] & 0xFF);
    }

    private void updateMemberSpeaking(int senderId, boolean isSpeaking) {
        for (int i = 0; i < membersContainer.getChildCount(); i++) {
            View child = membersContainer.getChildAt(i);
            if (child.getTag() != null && child.getTag().equals(senderId)) {
                if (child instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) child;
                    if (row.getChildCount() > 0) {
                        View dot = row.getChildAt(0);
                        if (isSpeaking) {
                            dot.setBackgroundResource(R.drawable.bg_speaking_dot);
                        } else {
                            dot.setBackgroundResource(R.drawable.bg_online_dot);
                        }
                    }
                }
                break;
            }
        }
    }

    private void setupMicToggle() {
        btnMicToggle.setText(getString(R.string.push_to_talk));
        btnMicToggle.setBackgroundResource(R.drawable.bg_button_primary);
        btnMicToggle.setTextColor(0xFFFFFFFF);
        btnMicToggle.setOnClickListener(v -> {
            if (isPTTMode) {
                isPTTMode = false;
                audioRecorder.startRecording();
                isAlwaysOnActive = true;
                updateMemberSpeaking(mySenderId, true);

                btnMicToggle.setText(getString(R.string.always_on));
                btnMicToggle.setBackgroundResource(R.drawable.bg_button_secondary);
                btnMicToggle.setTextColor(getColorRes(R.color.text_secondary));

                AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
                fadeOut.setDuration(200);
                fadeOut.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation animation) {}
                    @Override public void onAnimationEnd(Animation animation) { btnPTT.setVisibility(View.GONE); }
                    @Override public void onAnimationRepeat(Animation animation) {}
                });
                btnPTT.startAnimation(fadeOut);
                tvPttHint.setText(getString(R.string.mic_active));
            } else {
                isPTTMode = true;
                if (isAlwaysOnActive) {
                    audioRecorder.stopRecording();
                    isAlwaysOnActive = false;
                    updateMemberSpeaking(mySenderId, false);
                }
                btnMicToggle.setText(getString(R.string.push_to_talk));
                btnMicToggle.setBackgroundResource(R.drawable.bg_button_primary);
                btnMicToggle.setTextColor(0xFFFFFFFF);
                btnPTT.setVisibility(View.VISIBLE);
                AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
                fadeIn.setDuration(200);
                btnPTT.startAnimation(fadeIn);
                tvPttHint.setText(getString(R.string.hold_to_speak));
            }
        });
    }

    private void setupPTTButton() {
        btnPTT.setOnTouchListener((v, event) -> {
            if (!isPTTMode) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mySenderId <= 0 || audioRecorder == null) return false;
                btnPTT.setText("🔴");
                tvPttHint.setText(getString(R.string.speaking));
                audioRecorder.startRecording();
                startPttPulseAnimation();
                updateMemberSpeaking(mySenderId, true);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnPTT.setText("🎤");
                tvPttHint.setText(getString(R.string.hold_to_speak));
                audioRecorder.stopRecording();
                stopPttPulseAnimation();
                updateMemberSpeaking(mySenderId, false);
            }
            return true;
        });
    }

    private void startPttPulseAnimation() {
        if (isPttPulsing) return;
        isPttPulsing = true;
        pttPulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPttPulsing) return;
                AlphaAnimation ripple2 = new AlphaAnimation(0f, 0.6f);
                ripple2.setDuration(400);
                ripple2.setRepeatMode(Animation.REVERSE);
                ripple2.setRepeatCount(1);
                pttRipple2.startAnimation(ripple2);

                mainHandler.postDelayed(() -> {
                    if (!isPttPulsing) return;
                    AlphaAnimation ripple3 = new AlphaAnimation(0f, 0.4f);
                    ripple3.setDuration(500);
                    ripple3.setRepeatMode(Animation.REVERSE);
                    ripple3.setRepeatCount(1);
                    pttRipple3.startAnimation(ripple3);
                }, 200);

                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(pttPulseRunnable);
    }

    private void stopPttPulseAnimation() {
        isPttPulsing = false;
        if (pttPulseRunnable != null) mainHandler.removeCallbacks(pttPulseRunnable);
        pttRipple2.clearAnimation();
        pttRipple2.setAlpha(0f);
        pttRipple3.clearAnimation();
        pttRipple3.setAlpha(0f);
    }

    private void setupLeaveButton() {
        TextView btnLeave = findViewById(R.id.btnLeaveRoom);
        btnLeave.setOnClickListener(v -> handleLeaveOrDestroyRoom());
    }

    private void handleLeaveOrDestroyRoom() {
        if (udpSender != null && udpSender.isReady()) {
            new Thread(() -> {
                if (isHost) {
                    byte[] destroyPacket = new byte[]{PACKET_ROOM_DESTROYED};
                    for (int i = 0; i < 5; i++) {
                        udpSender.send(destroyPacket);
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                } else {
                    byte[] leavePacket = new byte[]{PACKET_CLIENT_LEAVE};
                    for (int i = 0; i < 3; i++) {
                        udpSender.send(leavePacket);
                        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                    }
                }
            }).start();
        }
        cleanup();
        finish();
    }

    @Override
    public void onAudioReceived(int senderId, byte[] audioData) {
        if (audioData == null || audioData.length == 0) return;

        if (audioData.length == CONTROL_PACKET_SIZE || audioData.length == MEMBER_CONTROL_PACKET_SIZE) {
            byte controlByte = audioData[0];
            long now = System.currentTimeMillis();

            if (controlByte == PACKET_PING_CLIENT) {
                membersMap.put(senderId, now);
                if (!knownRemoteMembers.contains(senderId)) {
                    knownRemoteMembers.add(senderId);
                    String userName = getString(R.string.user_prefix) + (senderId % 1000);
                    mainHandler.post(() -> addMemberToUI(senderId, userName, false, false));
                    if (isHost) broadcastMemberJoined(senderId);
                }
            } else if (controlByte == PACKET_PING_HOST) {
                hostSenderId = senderId;
                membersMap.put(senderId, now);
                if (!knownRemoteMembers.contains(senderId)) {
                    knownRemoteMembers.add(senderId);
                    String userName = "مدیر دستگاه " + (senderId % 1000);
                    mainHandler.post(() -> addMemberToUI(senderId, userName, false, true));
                }
            } else if (controlByte == PACKET_ROOM_DESTROYED) {
                if (!isHost) {
                    mainHandler.post(() -> {
                        VoxToast.error(VoiceRoomActivity.this, "اتاق توسط مدیر بسته شد.", 4000);
                        cleanup();
                        finish();
                    });
                }
            } else if (controlByte == PACKET_CLIENT_LEAVE) {
                membersMap.remove(senderId);
                knownRemoteMembers.remove(senderId);
                mainHandler.post(() -> removeMemberFromUI(senderId));
            } else if (controlByte == PACKET_MEMBER_JOINED && audioData.length == MEMBER_CONTROL_PACKET_SIZE) {
                int memberId = readIntFromBytes(audioData, 1);
                if (memberId == mySenderId) return;
                if (!knownRemoteMembers.contains(memberId)) {
                    knownRemoteMembers.add(memberId);
                    membersMap.put(memberId, now);
                    String userName = getString(R.string.user_prefix) + (memberId % 1000);
                    mainHandler.post(() -> addMemberToUI(memberId, userName, false, false));
                }
            } else if (controlByte == PACKET_MEMBER_LEFT && audioData.length == MEMBER_CONTROL_PACKET_SIZE) {
                int memberId = readIntFromBytes(audioData, 1);
                membersMap.remove(memberId);
                knownRemoteMembers.remove(memberId);
                mainHandler.post(() -> removeMemberFromUI(memberId));
            }
            return;
        }

        if (audioPlayer != null) {
            audioPlayer.play(audioData);
        }

        long now = System.currentTimeMillis();
        boolean isNewMember = !membersMap.containsKey(senderId);
        membersMap.put(senderId, now);

        if (isNewMember) {
            if (!knownRemoteMembers.contains(senderId)) {
                knownRemoteMembers.add(senderId);
            }
            String userName = getString(R.string.user_prefix) + (senderId % 1000);
            mainHandler.post(() -> addMemberToUI(senderId, userName, false, false));
        }

        mainHandler.post(() -> updateMemberSpeaking(senderId, true));
        mainHandler.postDelayed(() -> {
            Long lastSeen = membersMap.get(senderId);
            if (lastSeen != null && lastSeen == now) {
                mainHandler.post(() -> updateMemberSpeaking(senderId, false));
            }
        }, 600);
    }

    @Override
    public void onChunkReady(byte[] audioChunk) {
        if (udpSender != null) udpSender.send(audioChunk);
    }

    private void cleanup() {
        if (presenceRunnable != null) presenceHandler.removeCallbacks(presenceRunnable);
        if (memberCheckRunnable != null) mainHandler.removeCallbacks(memberCheckRunnable);
        if (audioRecorder != null) audioRecorder.release();
        if (audioPlayer != null) audioPlayer.stop();
        if (udpReceiver != null) udpReceiver.stop();
        if (udpSender != null) udpSender.close();
        stopPttPulseAnimation();

        // ✅ ریست کردن AudioManager
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private int getColorRes(int resId) {
        return getResources().getColor(resId, null);
    }

    private void playEnterAnimation() {
        ScaleAnimation scaleUp = new ScaleAnimation(0.5f, 1f, 0.5f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
        scaleUp.setDuration(400);
        scaleUp.setInterpolator(new OvershootInterpolator(1.2f));
        btnPTT.startAnimation(scaleUp);

        AlphaAnimation badgeIn = new AlphaAnimation(0f, 1f);
        badgeIn.setDuration(500);
        badgeIn.setStartOffset(200);
        TextView liveBadge = findViewById(R.id.tvLiveBadge);
        if (liveBadge != null) liveBadge.startAnimation(badgeIn);
    }
}