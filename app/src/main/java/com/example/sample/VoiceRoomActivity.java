package com.example.sample;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.sample.model.Member;
import com.example.sample.service.VoxLinkService;
import com.example.sample.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceRoomActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 2001;

    private TextView btnPTT, tvPttHint, tvRoomSubtitle, btnMicToggle, tvMemberCount;
    private LinearLayout membersContainer;
    private View pttOuter, pttRipple2, pttRipple3;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private VoxLinkService.LocalBinder serviceBinder;
    private boolean serviceBound = false;

    private boolean isHost = false;
    private String hostAddress;
    private String username;
    private volatile int mySenderId = -1;

    private boolean isPTTMode = true;
    private boolean isPttPulsing = false;

    private final Map<Integer, Member> memberMap = new ConcurrentHashMap<>();
    private Runnable pttPulseRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceBinder = (VoxLinkService.LocalBinder) binder;
            serviceBound = true;
            serviceBinder.setListener(voxLinkListener);
            serviceBinder.startRoom(username, isHost, hostAddress);

            mySenderId = serviceBinder.getMySenderId();
            isHost = serviceBinder.isHost();

            runOnUiThread(() -> {
                if (isHost) {
                    tvRoomSubtitle.setText(getString(R.string.you_are_host));
                } else if (hostAddress != null) {
                    tvRoomSubtitle.setText(getString(R.string.connected_to, hostAddress));
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBinder = null;
            serviceBound = false;
        }
    };

    private final VoxLinkService.VoxLinkListener voxLinkListener = new VoxLinkService.VoxLinkListener() {
        @Override
        public void onMemberJoined(Member member) {
            runOnUiThread(() -> {
                if (member == null) return;
                memberMap.put(member.id, member);
                addOrUpdateMemberRow(member);
                if (member.id == mySenderId && member.isHost) {
                    isHost = true;
                }
            });
        }

        @Override
        public void onMemberLeft(int memberId) {
            runOnUiThread(() -> {
                memberMap.remove(memberId);
                removeMemberFromUI(memberId);
            });
        }

        @Override
        public void onMemberSpeaking(int memberId, boolean speaking) {
            runOnUiThread(() -> updateMemberSpeaking(memberId, speaking));
        }

        @Override
        public void onHostChanged(int hostId) {
            runOnUiThread(() -> {
                for (Member m : memberMap.values()) {
                    m.isHost = (m.id == hostId);
                }
                for (Map.Entry<Integer, Member> e : memberMap.entrySet()) {
                    updateMemberRow(e.getValue());
                }
            });
        }

        @Override
        public void onRoomDestroyed() {
            runOnUiThread(() -> {
                VoxToast.error(VoiceRoomActivity.this, getString(R.string.room_closed), 4000);
                finishRoom();
            });
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> {
                VoxToast.error(VoiceRoomActivity.this, message, 4500);
                if (!serviceBound || serviceBinder == null) {
                    mainHandler.postDelayed(VoiceRoomActivity.this::finish, 2000);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_room);
        findViews();

        SharedPreferences prefs = getSharedPreferences("voxlink", MODE_PRIVATE);
        username = prefs.getString("username", getString(R.string.user));

        String role = getIntent().getStringExtra("role");
        hostAddress = getIntent().getStringExtra("hostAddress");
        isHost = "host".equals(role);

        playEnterAnimation();
        setupMicToggle();
        setupPTTButton();
        setupLeaveButton();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishRoom();
            }
        });

        if (checkAndRequestPermissions()) {
            startAndBindService();
        }
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

    private boolean checkAndRequestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }

        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        boolean recordGranted = false;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) &&
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                recordGranted = true;
            }
        }

        if (recordGranted) {
            startAndBindService();
        } else {
            VoxToast.error(this, getString(R.string.mic_permission_denied), 4000);
            finish();
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, VoxLinkService.class);
        ContextCompat.startForegroundService(this, intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupMicToggle() {
        if (btnMicToggle == null) return;
        btnMicToggle.setText(getString(R.string.push_to_talk));
        btnMicToggle.setBackgroundResource(R.drawable.bg_button_primary);
        btnMicToggle.setTextColor(0xFFFFFFFF);

        btnMicToggle.setOnClickListener(v -> {
            if (serviceBinder == null) return;
            serviceBinder.toggleAlwaysOn();
            updateMicToggleUI();
        });
    }

    private void updateMicToggleUI() {
        if (serviceBinder == null) return;
        boolean alwaysOn = serviceBinder.isAlwaysOn();
        if (alwaysOn) {
            btnMicToggle.setText(getString(R.string.always_on));
            btnMicToggle.setBackgroundResource(R.drawable.bg_button_secondary);
            btnMicToggle.setTextColor(getColorRes(R.color.text_secondary));
            btnPTT.setVisibility(View.GONE);
            tvPttHint.setText(getString(R.string.mic_active));
        } else {
            btnMicToggle.setText(getString(R.string.push_to_talk));
            btnMicToggle.setBackgroundResource(R.drawable.bg_button_primary);
            btnMicToggle.setTextColor(0xFFFFFFFF);
            btnPTT.setVisibility(View.VISIBLE);
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(200);
            btnPTT.startAnimation(fadeIn);
            tvPttHint.setText(getString(R.string.hold_to_speak));
        }
    }

    private void setupPTTButton() {
        if (btnPTT == null) return;
        btnPTT.setOnTouchListener((v, event) -> {
            if (serviceBinder == null) return false;
            if (!isPTTMode) return false;

            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                btnPTT.setText("🔴");
                tvPttHint.setText(getString(R.string.speaking));
                serviceBinder.setPttPressed(true);
                startPttPulseAnimation();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                btnPTT.setText("🎤");
                tvPttHint.setText(getString(R.string.hold_to_speak));
                serviceBinder.setPttPressed(false);
                stopPttPulseAnimation();
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
        if (btnLeave != null) {
            btnLeave.setOnClickListener(v -> finishRoom());
        }
    }

    private void finishRoom() {
        if (serviceBinder != null) {
            serviceBinder.leaveRoom();
        }
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            serviceBound = false;
        }
        stopPttPulseAnimation();
        finish();
    }

    private void addOrUpdateMemberRow(Member member) {
        View existing = findMemberRow(member.id);
        if (existing != null) {
            updateMemberRow(member);
            return;
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
        row.setTag(member.id);

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(18, 18);
        dotParams.rightMargin = 12;
        dot.setLayoutParams(dotParams);
        dot.setBackgroundResource(R.drawable.bg_online_dot);
        dot.setTag("dot_" + member.id);

        TextView tvName = new TextView(this);
        String displayName = member.id == mySenderId
                ? member.username + " (" + getString(R.string.you) + ")"
                : member.username;
        tvName.setText(displayName);
        tvName.setTextColor(getColorRes(R.color.text_primary));
        tvName.setTextSize(13);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvRole = new TextView(this);
        tvRole.setTextSize(12);
        tvRole.setTag("role_" + member.id);
        setRoleText(tvRole, member);

        row.addView(dot);
        row.addView(tvName);
        row.addView(tvRole);

        if (isHost && member.id != mySenderId) {
            row.setOnLongClickListener(v -> {
                showHostMenu(row, member);
                return true;
            });
        }

        membersContainer.addView(row);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(300);
        row.startAnimation(fadeIn);
        updateMemberCount();
    }

    private void updateMemberRow(Member member) {
        View row = findMemberRow(member.id);
        if (row == null) return;

        TextView tvName = (TextView) ((LinearLayout) row).getChildAt(1);
        TextView tvRole = row.findViewWithTag("role_" + member.id);
        if (tvName != null) {
            tvName.setText(member.id == mySenderId
                    ? member.username + " (" + getString(R.string.you) + ")"
                    : member.username);
        }
        if (tvRole != null) {
            setRoleText(tvRole, member);
        }
    }

    private void setRoleText(TextView tvRole, Member member) {
        StringBuilder sb = new StringBuilder();
        if (member.isMuted.get()) {
            sb.append("🔇 ");
        }
        if (member.isHost) {
            sb.append("👑 ");
            sb.append(getString(R.string.host));
            tvRole.setTextColor(0xFFFBBF24);
        } else {
            sb.append("👤 ");
            sb.append(getString(R.string.user));
            tvRole.setTextColor(0xFF94A3B8);
        }
        tvRole.setText(sb.toString());
    }

    private void showHostMenu(View anchor, Member member) {
        PopupMenu popup = new PopupMenu(this, anchor);
        String muteLabel = member.isMuted.get() ? getString(R.string.unmute) : getString(R.string.mute);
        popup.getMenu().add(0, 1, 0, muteLabel);
        popup.getMenu().add(0, 2, 0, getString(R.string.kick));

        popup.setOnMenuItemClickListener(item -> {
            if (serviceBinder == null) return false;
            int id = item.getItemId();
            if (id == 1) {
                boolean newMuted = !member.isMuted.get();
                member.isMuted.set(newMuted);
                serviceBinder.muteMember(member.id, newMuted);
                updateMemberRow(member);
                return true;
            } else if (id == 2) {
                showKickConfirmation(member.id);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showKickConfirmation(int memberId) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.kick_confirm_title))
                .setMessage(getString(R.string.kick_confirm_message))
                .setPositiveButton(getString(R.string.yes), (dialog, which) -> {
                    if (serviceBinder != null) {
                        serviceBinder.kickMember(memberId);
                    }
                    memberMap.remove(memberId);
                    removeMemberFromUI(memberId);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private View findMemberRow(int memberId) {
        for (int i = 0; i < membersContainer.getChildCount(); i++) {
            View child = membersContainer.getChildAt(i);
            if (child.getTag() != null && child.getTag().equals(memberId)) {
                return child;
            }
        }
        return null;
    }

    private void removeMemberFromUI(int memberId) {
        View row = findMemberRow(memberId);
        if (row == null) return;
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(300);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationEnd(Animation animation) {
                membersContainer.removeView(row);
                updateMemberCount();
            }
            @Override public void onAnimationRepeat(Animation animation) {}
        });
        row.startAnimation(fadeOut);
    }

    private void updateMemberSpeaking(int memberId, boolean speaking) {
        View row = findMemberRow(memberId);
        if (row == null) return;
        View dot = row.findViewWithTag("dot_" + memberId);
        if (dot != null) {
            dot.setBackgroundResource(speaking ? R.drawable.bg_speaking_dot : R.drawable.bg_online_dot);
        }
    }

    private void updateMemberCount() {
        if (tvMemberCount == null) return;
        tvMemberCount.setText(getString(R.string.member_count, membersContainer.getChildCount()));
    }

    private int getColorRes(int resId) {
        return getResources().getColor(resId, null);
    }

    private void playEnterAnimation() {
        ScaleAnimation scaleUp = new ScaleAnimation(0.5f, 1f, 0.5f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
        scaleUp.setDuration(400);
        scaleUp.setInterpolator(new OvershootInterpolator(1.2f));
        if (btnPTT != null) btnPTT.startAnimation(scaleUp);

        AlphaAnimation badgeIn = new AlphaAnimation(0f, 1f);
        badgeIn.setDuration(500);
        badgeIn.setStartOffset(200);
        TextView liveBadge = findViewById(R.id.tvLiveBadge);
        if (liveBadge != null) liveBadge.startAnimation(badgeIn);
    }

    @Override
    protected void onDestroy() {
        finishRoom();
        super.onDestroy();
    }
}
