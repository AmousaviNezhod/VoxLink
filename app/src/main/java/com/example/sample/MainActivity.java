package com.example.sample;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.sample.network.DiscoveryManager;
import com.example.sample.network.NetworkHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DiscoveryManager.DiscoveryListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_USERNAME = "username";
    private static final String PREFS_GROUP = "group_name";

    private DrawerLayout drawerLayout;
    private LinearLayout mainContent;
    private TextView btnCreateGroup, btnJoinGroup, btnCancelSearch, tvStatus;
    private View btnSettings;

    // Settings Views
    private SwitchCompat switchDarkMode;
    private EditText etUsername;

    private DiscoveryManager discoveryManager;
    private boolean isSearching = false;
    private SharedPreferences prefs;
    private Runnable pendingPermissionAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("voxlink", MODE_PRIVATE);
        applyThemeFromPrefs();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViews();
        setupSettingsDrawer();

        if (mainContent != null) {
            mainContent.setAlpha(0f);
            mainContent.setTranslationY(UiHelper.dpToPx(this, 30));
            mainContent.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiHelper.shouldAnimate(this) ? 600 : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        showOnboardingIfNeeded();

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
            });
        }

        if (btnCancelSearch != null) {
            btnCancelSearch.setOnClickListener(v -> stopSearchingMode());
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    if (isSearching) {
                        stopSearchingMode();
                    } else {
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }
                }
            }
        });

        if (btnCreateGroup != null) {
            btnCreateGroup.setOnClickListener(v -> {
                pendingPermissionAction = this::startCreateGroup;
                if (checkAndRequestPermissions()) startCreateGroup();
            });
        }

        if (btnJoinGroup != null) {
            btnJoinGroup.setOnClickListener(v -> {
                pendingPermissionAction = this::startJoinGroup;
                if (checkAndRequestPermissions()) {
                    if (!NetworkHelper.isLocalNetworkAvailable(this)) {
                        setStatus("وای‌فای یا هات‌اسپات متصل نیست!", 0xFFEF4444);
                        return;
                    }
                    startJoinGroup();
                }
            });
        }
    }

    private void findViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        mainContent = findViewById(R.id.mainContent);
        btnSettings = findViewById(R.id.btnSettings);
        tvStatus = findViewById(R.id.tvStatus);
        btnCreateGroup = findViewById(R.id.btnCreateGroup);
        btnJoinGroup = findViewById(R.id.btnJoinGroup);
        btnCancelSearch = findViewById(R.id.btnCancelSearch);
        switchDarkMode = findViewById(R.id.switchDarkMode);
        etUsername = findViewById(R.id.etUsername);
    }

    private void setupSettingsDrawer() {
        boolean isDarkMode = prefs.getBoolean("dark_mode", true);
        if (switchDarkMode != null) {
            switchDarkMode.setChecked(isDarkMode);
            switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("dark_mode", isChecked);
                editor.apply();
                AppCompatDelegate.setDefaultNightMode(
                        isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
            });
        }

        String savedUsername = prefs.getString(PREFS_USERNAME, "User");
        if (etUsername != null) {
            etUsername.setText(savedUsername);
            etUsername.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(PREFS_USERNAME, s.toString().trim());
                    editor.apply();
                }
            });
        }
    }

    private void applyThemeFromPrefs() {
        boolean isDarkMode = prefs.getBoolean("dark_mode", true);
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        List<String> finalPermissions = new ArrayList<>();
        for (String perm : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                finalPermissions.add(perm);
            }
        }

        if (!finalPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, finalPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                setStatus("دسترسی‌ها تایید شد.", 0xFF22C55E);
                if (pendingPermissionAction != null) {
                    pendingPermissionAction.run();
                    pendingPermissionAction = null;
                }
            } else {
                setStatus("تایید دسترسی میکروفون و شبکه الزامی است!", 0xFFEF4444);
                pendingPermissionAction = null;
            }
        }
    }

    private void showOnboardingIfNeeded() {
        String savedUsername = prefs.getString(PREFS_USERNAME, "");
        String savedGroup = prefs.getString(PREFS_GROUP, "");
        if (!savedUsername.isEmpty() && !savedGroup.isEmpty()) {
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding, null);
        TextInputLayout tilUsername = view.findViewById(R.id.tilUsername);
        TextInputLayout tilGroup = view.findViewById(R.id.tilGroup);
        TextInputEditText etName = view.findViewById(R.id.etUsername);
        TextInputEditText etGroup = view.findViewById(R.id.etGroup);
        TextView tvError = view.findViewById(R.id.tvOnboardingError);

        tilUsername.setHint(getString(R.string.username));
        tilGroup.setHint(getString(R.string.group_name));
        etName.setText(savedUsername);
        etGroup.setText(savedGroup);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_message)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.VoxLinkDialogAnimation);
        }

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String group = etGroup.getText().toString().trim();
                boolean valid = true;
                if (name.isEmpty()) {
                    tilUsername.setError(getString(R.string.username_required));
                    valid = false;
                } else {
                    tilUsername.setError(null);
                }
                if (group.isEmpty()) {
                    tilGroup.setError(getString(R.string.group_required));
                    valid = false;
                } else {
                    tilGroup.setError(null);
                }
                if (!valid) {
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                tvError.setVisibility(View.GONE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREFS_USERNAME, name);
                editor.putString(PREFS_GROUP, group);
                editor.apply();
                if (etUsername != null) etUsername.setText(name);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private String getSavedUsername() {
        return prefs.getString(PREFS_USERNAME, "User").trim();
    }

    private String getSavedGroup() {
        return prefs.getString(PREFS_GROUP, "").trim();
    }

    private void startCreateGroup() {
        if (!NetworkHelper.isLocalNetworkAvailable(this)) {
            setStatus("وای‌فای یا هات‌اسپات متصل نیست!", 0xFFEF4444);
            return;
        }
        String group = getSavedGroup();
        if (group.isEmpty()) {
            setStatus("ابتدا نام گروه را در تنظیمات وارد کنید", 0xFFEF4444);
            return;
        }
        setStatus("در حال راه‌اندازی گروه...", 0xFFBA7517);
        if (discoveryManager != null) discoveryManager.stopAll();
        goToVoiceRoom("host", "127.0.0.1", group);
    }

    private void startJoinGroup() {
        String group = getSavedGroup();
        if (group.isEmpty()) {
            setStatus("ابتدا نام گروه را در تنظیمات وارد کنید", 0xFFEF4444);
            return;
        }
        isSearching = true;
        setStatus("در حال جستجوی گروه ووکس‌لینک...", 0xFFBA7517);
        if (btnCreateGroup != null) btnCreateGroup.setEnabled(false);
        if (btnJoinGroup != null) btnJoinGroup.setEnabled(false);
        if (btnCancelSearch != null) btnCancelSearch.setVisibility(View.VISIBLE);

        if (discoveryManager != null) discoveryManager.stopAll();
        discoveryManager = new DiscoveryManager(this, group, this);
        discoveryManager.startDiscovery();
    }

    private void stopSearchingMode() {
        isSearching = false;
        if (discoveryManager != null) discoveryManager.stopDiscovery();
        setStatus("آماده اتصال", 0xFF22C55E);
        if (btnCreateGroup != null) btnCreateGroup.setEnabled(true);
        if (btnJoinGroup != null) btnJoinGroup.setEnabled(true);
        if (btnCancelSearch != null) btnCancelSearch.setVisibility(View.GONE);
    }

    private void setStatus(String message, int color) {
        if (tvStatus != null) {
            tvStatus.setText(message);
            tvStatus.setTextColor(color);
        }
    }

    private void goToVoiceRoom(String role, String hostIp, String groupName) {
        Intent intent = new Intent(this, VoiceRoomActivity.class);
        intent.putExtra("role", role);
        intent.putExtra("hostAddress", hostIp);
        intent.putExtra("groupName", groupName);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_up, R.anim.fade_out);
    }

    @Override
    public void onGroupFound(String hostAddress, int port) {
        runOnUiThread(() -> {
            setStatus("گروه پیدا شد! ورود...", 0xFF22C55E);
            if (discoveryManager != null) discoveryManager.stopDiscovery();
            goToVoiceRoom("client", hostAddress, getSavedGroup());
        });
    }

    @Override
    public void onGroupLost() {
        runOnUiThread(() -> setStatus("اتصال با گروه قطع شد.", 0xFFEF4444));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            setStatus("خطا در شبکه: " + message, 0xFFEF4444);
            stopSearchingMode();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (discoveryManager != null) discoveryManager.stopAll();
        stopSearchingMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryManager != null) discoveryManager.stopAll();
    }
}
