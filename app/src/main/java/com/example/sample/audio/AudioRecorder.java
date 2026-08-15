package com.example.sample.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.example.sample.util.Constants;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    private AudioRecord audioRecord;
    private Thread recordThread;
    private boolean isRecording = false;

    public interface AudioChunkListener {
        void onChunkReady(byte[] audioChunk);
    }

    private AudioChunkListener listener;

    public AudioRecorder(AudioChunkListener listener) {
        this.listener = listener;
    }

    public boolean prepare() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized");
                audioRecord.release();
                audioRecord = null;
                return false;
            }

            Log.d(TAG, "AudioRecorder prepared with buffer: " + bufferSize);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error preparing: " + e.getMessage());
            return false;
        }
    }

    public void startRecording() {
        if (isRecording || audioRecord == null) return;
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) return;

        audioRecord.startRecording();
        isRecording = true;

        recordThread = new Thread(() -> {
            byte[] buffer = new byte[Constants.FRAME_SIZE * 2];
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && listener != null) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                    listener.onChunkReady(chunk);
                }
            }
        });

        recordThread.setDaemon(true);
        recordThread.start();
        Log.d(TAG, "Recording started");
    }

    public void stopRecording() {
        isRecording = false;
        if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop();
        }
        Log.d(TAG, "Recording stopped");
    }

    public void release() {
        stopRecording();
        if (recordThread != null) {
            try {
                recordThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordThread = null;
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }
}