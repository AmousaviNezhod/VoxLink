package com.example.sample.audio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.example.sample.util.Constants;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    private AudioTrack audioTrack;
    private boolean ready = false;

    public boolean prepare() {
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBufferSize < 0) {
                Log.e(TAG, "Invalid min buffer size");
                return false;
            }
            // Keep 5 frames of buffer to reduce underrun while keeping latency low.
            int bufferSize = Math.max(minBufferSize * 2, Constants.FRAME_SIZE * 2 * 5);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setSampleRate(Constants.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                audioTrack = new AudioTrack(
                        audioAttributes,
                        audioFormat,
                        bufferSize,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                );
            }

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize");
                releaseTrack();
                return false;
            }

            audioTrack.play();
            ready = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error preparing AudioPlayer: " + e.getMessage());
            releaseTrack();
            return false;
        }
    }

    public boolean setPreferredDevice(AudioDeviceInfo device) {
        if (audioTrack == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        return audioTrack.setPreferredDevice(device);
    }

    public AudioTrack getAudioTrack() {
        return audioTrack;
    }

    public void play(byte[] audioData) {
        if (!isReady() || audioData == null || audioData.length == 0) return;

        int offset = 0;
        int length = audioData.length;
        while (offset < length) {
            int written = audioTrack.write(audioData, offset, length - offset);
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write returned " + written);
                break;
            }
            offset += written;
        }
    }

    public void writeShorts(short[] samples) {
        if (samples == null) return;
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        play(bytes);
    }

    public void stop() {
        ready = false;
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioTrack: " + e.getMessage());
            }
            releaseTrack();
        }
    }

    public boolean isReady() {
        return ready && audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED;
    }

    private void releaseTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioTrack: " + e.getMessage());
            }
            audioTrack = null;
        }
    }
}