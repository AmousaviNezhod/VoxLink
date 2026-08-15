package com.example.sample.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;

/**
 * AEC/NS/AGC setup and audio routing helpers.
 * This skeleton defines the API used by AudioRecorder; the audio child will implement it.
 */
public class AudioEffects {

    public static boolean isAecAvailable() {
        // TODO
        return false;
    }

    public static boolean isNsAvailable() {
        // TODO
        return false;
    }

    public static boolean isAgcAvailable() {
        // TODO
        return false;
    }

    public static void attach(AudioRecord audioRecord) {
        // TODO: create AcousticEchoCanceler, NoiseSuppressor, AutomaticGainControl
    }

    public static void configureForCommunication(Context context, AudioManager audioManager, boolean active) {
        // TODO: set mode, speakerphone, Bluetooth SCO
    }

    public static void release() {
        // TODO
    }
}
