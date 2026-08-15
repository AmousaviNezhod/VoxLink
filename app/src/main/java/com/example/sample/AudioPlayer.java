package com.example.sample;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    private AudioTrack audioTrack;
    private boolean isPlaying = false;

    public boolean prepare() {
        try {
            int bufferSize = AudioTrack.getMinBufferSize(
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(Constants.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            audioTrack.play();
            isPlaying = true;
            Log.d(TAG, "AudioPlayer prepared with buffer: " + bufferSize);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error preparing AudioPlayer: " + e.getMessage());
            return false;
        }
    }

    public void play(byte[] audioData) {
        if (!isPlaying || audioTrack == null) return;
        audioTrack.write(audioData, 0, audioData.length);
    }

    public void stop() {
        isPlaying = false;
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }
}