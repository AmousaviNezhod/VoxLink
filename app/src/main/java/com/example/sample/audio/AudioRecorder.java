package com.example.sample.audio;

import android.annotation.SuppressLint;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.example.sample.util.Constants;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean isRecording = false;
    private AudioChunkListener listener;

    public interface AudioChunkListener {
        void onChunkReady(byte[] audioChunk);
    }

    public AudioRecorder(AudioChunkListener listener) {
        this.listener = listener;
    }

    public void setAudioChunkListener(AudioChunkListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public boolean prepare() {
        try {
            int minBufferSize = AudioRecord.getMinBufferSize(
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBufferSize < 0) {
                Log.e(TAG, "Invalid min buffer size");
                return false;
            }
            int bufferSize = Math.max(minBufferSize * 2, Constants.FRAME_SIZE * 2);

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

            AudioEffects.attach(audioRecord);

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

        recordThread = new Thread(this::recordLoop);
        recordThread.setDaemon(true);
        recordThread.start();
        Log.d(TAG, "Recording started");
    }

    public void stopRecording() {
        isRecording = false;
        if (recordThread != null) {
            try {
                recordThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordThread = null;
        }
        if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioRecord: " + e.getMessage());
            }
        }
        Log.d(TAG, "Recording stopped");
    }

    public void release() {
        stopRecording();
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        AudioEffects.release();
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean setPreferredDevice(AudioDeviceInfo device) {
        if (audioRecord == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        return audioRecord.setPreferredDevice(device);
    }

    public AudioRecord getAudioRecord() {
        return audioRecord;
    }

    private void recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int frameBytes = Constants.FRAME_SIZE * 2;
        int minBufferSize = audioRecord.getBufferSizeInFrames() * 2;
        int readBufferSize = Math.max(minBufferSize, frameBytes * 4);
        byte[] readBuffer = new byte[readBufferSize];
        byte[] leftover = new byte[frameBytes];
        int leftoverLen = 0;

        while (isRecording) {
            int bytesRead = audioRecord.read(readBuffer, 0, readBufferSize);
            if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read error: " + bytesRead);
                continue;
            }
            if (bytesRead == 0) {
                continue;
            }

            byte[] combined = new byte[leftoverLen + bytesRead];
            System.arraycopy(leftover, 0, combined, 0, leftoverLen);
            System.arraycopy(readBuffer, 0, combined, leftoverLen, bytesRead);

            int processed = 0;
            int totalLen = combined.length;
            while (totalLen - processed >= frameBytes) {
                byte[] chunk = new byte[frameBytes];
                System.arraycopy(combined, processed, chunk, 0, frameBytes);
                if (listener != null) {
                    listener.onChunkReady(chunk);
                }
                processed += frameBytes;
            }

            leftoverLen = totalLen - processed;
            if (leftoverLen > 0) {
                System.arraycopy(combined, processed, leftover, 0, leftoverLen);
            }
        }
    }
}