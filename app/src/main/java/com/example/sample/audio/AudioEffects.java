package com.example.sample.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

/**
 * AEC/NS/AGC setup and audio routing helpers.
 */
public class AudioEffects {

    private static final String TAG = "AudioEffects";

    private static AcousticEchoCanceler aec;
    private static NoiseSuppressor ns;
    private static AutomaticGainControl agc;

    public static boolean isAecAvailable() {
        return AcousticEchoCanceler.isAvailable();
    }

    public static boolean isNsAvailable() {
        return NoiseSuppressor.isAvailable();
    }

    public static boolean isAgcAvailable() {
        return AutomaticGainControl.isAvailable();
    }

    public static void attach(AudioRecord audioRecord) {
        if (audioRecord == null) return;
        release();

        int sessionId = audioRecord.getAudioSessionId();

        if (isAecAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId);
            if (aec != null && !aec.getEnabled()) {
                aec.setEnabled(true);
            }
            Log.d(TAG, "AEC attached: " + (aec != null));
        }

        if (isNsAvailable()) {
            ns = NoiseSuppressor.create(sessionId);
            if (ns != null && !ns.getEnabled()) {
                ns.setEnabled(true);
            }
            Log.d(TAG, "NS attached: " + (ns != null));
        }

        if (isAgcAvailable()) {
            agc = AutomaticGainControl.create(sessionId);
            if (agc != null && !agc.getEnabled()) {
                agc.setEnabled(true);
            }
            Log.d(TAG, "AGC attached: " + (agc != null));
        }
    }

    public static void configureForCommunication(Context context, AudioManager audioManager, boolean active) {
        if (audioManager == null) return;
        try {
            if (active) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                if (audioManager.isBluetoothScoOn()) {
                    audioManager.stopBluetoothSco();
                }
                audioManager.setBluetoothScoOn(false);
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error configuring audio mode: " + e.getMessage());
        }
    }

    public static void release() {
        if (aec != null) {
            aec.release();
            aec = null;
        }
        if (ns != null) {
            ns.release();
            ns = null;
        }
        if (agc != null) {
            agc.release();
            agc = null;
        }
    }
}
