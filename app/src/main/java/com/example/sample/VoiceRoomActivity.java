package com.example.sample;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.sample.model.AudioDevice;
import com.example.sample.model.Member;
import com.example.sample.service.VoxLinkService;
import com.example.sample.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceRoomActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 2001;

    private ImageButton btnPTT;
    private TextView tvPttHint, tvRoomSubtitle, btnMicToggle, tvMemberCount;
    private View btnAudioDevices;
    private LinearLayout membersContainer;
    private View pttOuter, pttRipple2, pttRipple3;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private VoxLinkService.LocalBinder serviceBinder;
    private boolean serviceBound = false;

    private boolean isHost = false;
    private String hostAddress;
    private String groupName;
    private String username;
    private volatile int mySenderId = -1;

    private boolean isPTTMode = true;
    private boolean isPttPulsing = false;

    private final Map<Integer, Member> memberMap = new ConcurrentHashMap<>();
    private ObjectAnimator pulseAnimator2, pulseAnimator3;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceBinder = (VoxLinkService.LocalBinder) binder;
            serviceBound = true;
            serviceBinder.setListener(voxLinkListener);
            serviceBinder.startRoom(username, isHost, hostAddress, groupName);

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
        public void onBanned() {
            runOnUiThread(() -> {
                VoxToast.error(VoiceRoomActivity.this, getString(R.string.banned_message), 4000);
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
        groupName = getIntent().getStringExtra("groupName");
        if (groupName == null || groupName.isEmpty()) groupName = "default";
        isHost = "host".equals(role);

        playEnterAnimation();
        setupMicToggle();
        setupPTTButton();
        setupLeaveButton();
        setupAudioDeviceButton();

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
        btnAudioDevices = findViewById(R.id.btnAudioDevices);
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
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            serviceBinder.toggleAlwaysOn();
            updateMicToggleUI();
        });
    }

    private void updateMicToggleUI() {
        if (serviceBinder == null) return;
        boolean alwaysOn = serviceBinder.isAlwaysOn();
        if (alwaysOn) {
            UiHelper.setTextWithFade(btnMicToggle, getString(R.string.always_on), 100, 150);
            btnMicToggle.setBackgroundResource(R.drawable.bg_button_secondary);
            btnMicToggle.setTextColor(getColorRes(R.color.text_secondary));
            btnPTT.setVisibility(View.GONE);
            UiHelper.setTextWithFade(tvPttHint, getString(R.string.mic_active), 100, 150);
        } else {
            UiHelper.setTextWithFade(btnMicToggle, getString(R.string.push_to_talk), 100, 150);
            btnMicToggle.setBackgroundResource(R.drawable.bg_button_primary);
            btnMicToggle.setTextColor(0xFFFFFFFF);
            btnPTT.setVisibility(View.VISIBLE);
            UiHelper.fadeIn(btnPTT, 200);
            UiHelper.setTextWithFade(tvPttHint, getString(R.string.hold_to_speak), 100, 150);
        }
    }

    private void setupPTTButton() {
        if (btnPTT == null) return;
        btnPTT.setImageResource(R.drawable.ic_mic);
        btnPTT.setImageTintList(ColorStateList.valueOf(Color.WHITE));

        btnPTT.setOnTouchListener((v, event) -> {
            if (serviceBinder == null) return false;
            if (!isPTTMode) return false;

            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                updatePttVisuals(true);
                serviceBinder.setPttPressed(true);
                startPttPulseAnimation();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                updatePttVisuals(false);
                serviceBinder.setPttPressed(false);
                stopPttPulseAnimation();
            }
            return true;
        });
    }

    private void updatePttVisuals(boolean pressed) {
        if (btnPTT == null) return;
        if (pressed) {
            btnPTT.setImageResource(R.drawable.ic_stop);
            btnPTT.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            btnPTT.setBackgroundResource(R.drawable.ripple_ptt_active);
            UiHelper.setTextWithFade(tvPttHint, getString(R.string.speaking), 80, 120);
            UiHelper.pressFeedback(btnPTT, 0.92f, 150);
        } else {
            btnPTT.setImageResource(R.drawable.ic_mic);
            btnPTT.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            btnPTT.setBackgroundResource(R.drawable.ripple_ptt);
            UiHelper.setTextWithFade(tvPttHint, getString(R.string.hold_to_speak), 80, 120);
            UiHelper.pressFeedback(btnPTT, 1f, 200);
        }
    }

    private void startPttPulseAnimation() {
        if (isPttPulsing) return;
        isPttPulsing = true;

        pulseAnimator2 = ObjectAnimator.ofPropertyValuesHolder(
                pttRipple2,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f));
        pulseAnimator2.setDuration(900);
        pulseAnimator2.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator2.setRepeatMode(ObjectAnimator.RESTART);
        pulseAnimator2.setInterpolator(new DecelerateInterpolator());
        pulseAnimator2.start();

        mainHandler.postDelayed(() -> {
            if (!isPttPulsing || pulseAnimator3 != null) return;
            pulseAnimator3 = ObjectAnimator.ofPropertyValuesHolder(
                    pttRipple3,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0f));
            pulseAnimator3.setDuration(1100);
            pulseAnimator3.setRepeatCount(ObjectAnimator.INFINITE);
            pulseAnimator3.setRepeatMode(ObjectAnimator.RESTART);
            pulseAnimator3.setInterpolator(new DecelerateInterpolator());
            pulseAnimator3.start();
        }, 300);
    }

    private void stopPttPulseAnimation() {
        isPttPulsing = false;
        if (pulseAnimator2 != null) {
            pulseAnimator2.cancel();
            pulseAnimator2 = null;
        }
        if (pulseAnimator3 != null) {
            pulseAnimator3.cancel();
            pulseAnimator3 = null;
        }
        if (pttRipple2 != null) {
            pttRipple2.setScaleX(1f);
            pttRipple2.setScaleY(1f);
            pttRipple2.setAlpha(0f);
        }
        if (pttRipple3 != null) {
            pttRipple3.setScaleX(1f);
            pttRipple3.setScaleY(1f);
            pttRipple3.setAlpha(0f);
        }
    }

    private void setupLeaveButton() {
        TextView btnLeave = findViewById(R.id.btnLeaveRoom);
        if (btnLeave != null) {
            btnLeave.setOnClickListener(v -> finishRoom());
        }
    }

    private void setupAudioDeviceButton() {
        if (btnAudioDevices == null) return;
        btnAudioDevices.setOnClickListener(v -> showAudioDeviceMenu());
    }

    private void showAudioDeviceMenu() {
        if (serviceBinder == null) return;

        PopupMenu popup = new PopupMenu(this, btnAudioDevices);
        popup.getMenu().add(0, 1, 0, getString(R.string.change_input));
        popup.getMenu().add(0, 2, 0, getString(R.string.change_output));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                showDeviceSelectionDialog(true);
                return true;
            } else if (id == 2) {
                showDeviceSelectionDialog(false);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showDeviceSelectionDialog(boolean input) {
        if (serviceBinder == null) return;
        List<AudioDevice> devices = input ? serviceBinder.getAudioInputDevices() : serviceBinder.getAudioOutputDevices();
        if (devices == null || devices.isEmpty()) {
            VoxToast.error(this, getString(R.string.no_devices), 3000);
            return;
        }

        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            names[i] = devices.get(i).name;
        }

        AudioDevice current = input ? serviceBinder.getSelectedInputDevice() : serviceBinder.getSelectedOutputDevice();
        int checked = -1;
        if (current != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).id == current.id) {
                    checked = i;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(input ? getString(R.string.select_input) : getString(R.string.select_output))
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    AudioDevice selected = devices.get(which);
                    boolean ok = input ? serviceBinder.setAudioInputDevice(selected.id) : serviceBinder.setAudioOutputDevice(selected.id);
                    if (ok) {
                        VoxToast.success(this, "انتخاب شد: " + selected.name, 2000);
                    } else {
                        VoxToast.error(this, getString(R.string.device_switch_failed), 3000);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
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
        row.setMinimumHeight(dp(52));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        row.setBackgroundResource(R.drawable.bg_member_row);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setTag(member.id);

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMarginEnd(dp(12));
        dot.setLayoutParams(dotParams);
        dot.setBackgroundResource(R.drawable.bg_online_dot);
        dot.setTag("dot_" + member.id);

        TextView tvName = new TextView(this);
        String displayName = member.id == mySenderId
                ? member.username + " (" + getString(R.string.you) + ")"
                : member.username;
        tvName.setText(displayName);
        tvName.setTextColor(getColorRes(R.color.text_primary));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginEnd(dp(12));
        tvName.setLayoutParams(nameParams);
        tvName.setTag("name_" + member.id);

        LinearLayout badgeContainer = new LinearLayout(this);
        badgeContainer.setOrientation(LinearLayout.HORIZONTAL);
        badgeContainer.setGravity(Gravity.CENTER_VERTICAL);
        badgeContainer.setTag("badges_" + member.id);

        row.addView(dot);
        row.addView(tvName);
        row.addView(badgeContainer);

        updateMemberBadges(badgeContainer, member);

        if (isHost && member.id != mySenderId) {
            row.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showHostMenu(row, member);
                return true;
            });
        }

        membersContainer.addView(row);
        row.setAlpha(0f);
        row.setTranslationY(-dp(16));
        if (UiHelper.shouldAnimate(this)) {
            row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            row.setAlpha(1f);
            row.setTranslationY(0f);
        }
        updateMemberCount();
    }

    private void updateMemberRow(Member member) {
        View row = findMemberRow(member.id);
        if (row == null) return;

        TextView tvName = row.findViewWithTag("name_" + member.id);
        LinearLayout badgeContainer = row.findViewWithTag("badges_" + member.id);
        if (tvName != null) {
            tvName.setText(member.id == mySenderId
                    ? member.username + " (" + getString(R.string.you) + ")"
                    : member.username);
        }
        if (badgeContainer != null) {
            updateMemberBadges(badgeContainer, member);
        }
    }

    private void updateMemberBadges(LinearLayout container, Member member) {
        container.removeAllViews();
        int iconSize = dp(18);
        int gap = dp(4);

        if (member.isMuted.get()) {
            ImageView ivMute = new ImageView(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(iconSize, iconSize);
            p.setMarginEnd(gap);
            ivMute.setLayoutParams(p);
            ivMute.setImageResource(R.drawable.ic_mute);
            ivMute.setImageTintList(ColorStateList.valueOf(getColorRes(R.color.danger_text)));
            ivMute.setContentDescription(getString(R.string.mute));
            container.addView(ivMute);
        }

        ImageView ivRole = new ImageView(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(iconSize, iconSize);
        rp.setMarginEnd(gap);
        ivRole.setLayoutParams(rp);
        ivRole.setImageResource(member.isHost ? R.drawable.ic_host : R.drawable.ic_person);
        ivRole.setImageTintList(ColorStateList.valueOf(member.isHost ? getColorRes(R.color.accent_orange) : getColorRes(R.color.text_hint)));
        container.addView(ivRole);

        TextView tvRole = new TextView(this);
        tvRole.setText(member.isHost ? getString(R.string.host) : getString(R.string.user));
        tvRole.setTextColor(member.isHost ? getColorRes(R.color.accent_orange) : getColorRes(R.color.text_hint));
        tvRole.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        container.addView(tvRole);
    }

    private int dp(float value) {
        return UiHelper.dpToPx(this, value);
    }

    private void showHostMenu(View anchor, Member member) {
        PopupMenu popup = new PopupMenu(this, anchor);
        String muteLabel = member.isMuted.get() ? getString(R.string.unmute) : getString(R.string.mute);
        popup.getMenu().add(0, 1, 0, muteLabel);
        popup.getMenu().add(0, 2, 0, getString(R.string.kick));
        popup.getMenu().add(0, 3, 0, getString(R.string.ban));

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
            } else if (id == 3) {
                showBanConfirmation(member.id);
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

    private void showBanConfirmation(int memberId) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ban_confirm_title))
                .setMessage(getString(R.string.ban_confirm_message))
                .setPositiveButton(getString(R.string.yes), (dialog, which) -> {
                    if (serviceBinder != null) {
                        serviceBinder.banMember(memberId);
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
        row.animate().cancel();
        if (UiHelper.shouldAnimate(this)) {
            row.animate()
                    .alpha(0f)
                    .translationY(dp(16))
                    .setDuration(250)
                    .withEndAction(() -> {
                        membersContainer.removeView(row);
                        updateMemberCount();
                    })
                    .start();
        } else {
            membersContainer.removeView(row);
            updateMemberCount();
        }
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
        if (UiHelper.shouldAnimate(this)) {
            if (btnPTT != null) {
                btnPTT.setScaleX(0.6f);
                btnPTT.setScaleY(0.6f);
                btnPTT.setAlpha(0f);
                btnPTT.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(450)
                        .setInterpolator(new OvershootInterpolator(1.1f))
                        .start();
            }

            TextView liveBadge = findViewById(R.id.tvLiveBadge);
            if (liveBadge != null) {
                liveBadge.setAlpha(0f);
                liveBadge.animate()
                        .alpha(1f)
                        .setStartDelay(200)
                        .setDuration(350)
                        .start();
            }
        }
    }

    @Override
    protected void onDestroy() {
        finishRoom();
        super.onDestroy();
    }
}
