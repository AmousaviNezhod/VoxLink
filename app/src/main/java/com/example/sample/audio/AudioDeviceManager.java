package com.example.sample.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.example.sample.model.AudioDevice;

import java.util.ArrayList;
import java.util.List;

public class AudioDeviceManager {
    private static final String TAG = "AudioDeviceManager";

    private final AudioManager audioManager;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private int selectedInputId = -1;
    private int selectedOutputId = -1;

    public AudioDeviceManager(Context context) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void attachRecorder(AudioRecord audioRecord) {
        this.audioRecord = audioRecord;
    }

    public void attachPlayer(AudioTrack audioTrack) {
        this.audioTrack = audioTrack;
    }

    public List<AudioDevice> getInputDevices() {
        return collectDevices(true);
    }

    public List<AudioDevice> getOutputDevices() {
        return collectDevices(false);
    }

    private List<AudioDevice> collectDevices(boolean input) {
        List<AudioDevice> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return result;
        }
        int flag = input ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS;
        AudioDeviceInfo[] devices = audioManager.getDevices(flag);
        if (devices == null) return result;

        for (AudioDeviceInfo d : devices) {
            if (isSupportedType(d.getType(), input)) {
                result.add(new AudioDevice(d.getId(), AudioDevice.buildName(d, input), d.getType(), input, !input));
            }
        }
        return result;
    }

    private boolean isSupportedType(int type, boolean input) {
        if (input) {
            switch (type) {
                case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_TELEPHONY:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                    return true;
            }
        } else {
            switch (type) {
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                    return true;
            }
        }
        return false;
    }

    public boolean setInputDevice(int deviceId) {
        selectedInputId = deviceId;
        AudioDeviceInfo device = findDevice(deviceId, true);
        if (device == null || audioRecord == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean ok = audioRecord.setPreferredDevice(device);
            Log.d(TAG, "Set input device " + deviceId + " -> " + ok);
            return ok;
        }
        return false;
    }

    public boolean setOutputDevice(int deviceId) {
        selectedOutputId = deviceId;
        AudioDeviceInfo device = findDevice(deviceId, false);
        if (device == null) return false;
        applyAudioRouting(device);
        if (audioTrack != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean ok = audioTrack.setPreferredDevice(device);
            Log.d(TAG, "Set output device " + deviceId + " -> " + ok);
            return ok;
        }
        return false;
    }

    private AudioDeviceInfo findDevice(int deviceId, boolean input) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        int flag = input ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS;
        AudioDeviceInfo[] devices = audioManager.getDevices(flag);
        if (devices == null) return null;
        for (AudioDeviceInfo d : devices) {
            if (d.getId() == deviceId) return d;
        }
        return null;
    }

    private void applyAudioRouting(AudioDeviceInfo device) {
        if (audioManager == null || device == null) return;
        int type = device.getType();

        // Clear existing routing flags first.
        audioManager.setSpeakerphoneOn(false);
        if (audioManager.isBluetoothScoOn()) {
            audioManager.stopBluetoothSco();
        }
        audioManager.setBluetoothScoOn(false);

        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                audioManager.setSpeakerphoneOn(true);
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                // System routes automatically.
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            default:
                // Keep defaults (earpiece).
                break;
        }
    }

    public void resetRouting() {
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.stopBluetoothSco();
        }
        audioManager.setBluetoothScoOn(false);
        audioManager.setSpeakerphoneOn(false);
    }

    public AudioDevice getSelectedInputDevice() {
        AudioDeviceInfo info = findDevice(selectedInputId, true);
        return info == null ? null : new AudioDevice(info.getId(), AudioDevice.buildName(info, true), info.getType(), true, false);
    }

    public AudioDevice getSelectedOutputDevice() {
        AudioDeviceInfo info = findDevice(selectedOutputId, false);
        return info == null ? null : new AudioDevice(info.getId(), AudioDevice.buildName(info, false), info.getType(), false, true);
    }
}
