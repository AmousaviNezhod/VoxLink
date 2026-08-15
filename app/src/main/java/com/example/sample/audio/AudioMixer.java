package com.example.sample.audio;

import android.os.Process;
import android.util.Log;

import com.example.sample.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-sender jitter buffer + mixer + low-latency playback thread.
 */
public class AudioMixer {

    private static final String TAG = "AudioMixer";
    private static final int JITTER_PREFETCH = 3;
    private static final int JITTER_MAX_FUTURE = 20;
    private static final int JITTER_TIMEOUT_MS = 2000;

    private final AudioPlayer audioPlayer;
    private final int frameSize;
    private final int frameBytes;
    private final ConcurrentHashMap<Integer, JitterBuffer> senders = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile boolean muted = false;
    private Thread playbackThread;

    public AudioMixer(AudioPlayer audioPlayer, int frameSize) {
        this.audioPlayer = audioPlayer;
        this.frameSize = frameSize;
        this.frameBytes = frameSize * 2;
    }

    public void start() {
        if (running) return;
        running = true;
        playbackThread = new Thread(this::playbackLoop);
        playbackThread.setDaemon(true);
        playbackThread.start();
        Log.d(TAG, "AudioMixer started");
    }

    public void stop() {
        running = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
        }
        senders.clear();
        Log.d(TAG, "AudioMixer stopped");
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public void enqueue(int senderId, int sequence, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;

        short[] samples = decodePcm(pcm);
        JitterBuffer buffer = senders.computeIfAbsent(senderId, k -> new JitterBuffer());
        buffer.enqueue(sequence, samples);
    }

    private void playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        byte[] silenceBytes = new byte[frameBytes];
        short[] mixed = new short[frameSize];

        while (running) {
            long startMs = System.currentTimeMillis();

            List<Integer> expiredSenders = new ArrayList<>();
            List<JitterBuffer> activeBuffers = new ArrayList<>(senders.values());

            for (Map.Entry<Integer, JitterBuffer> entry : senders.entrySet()) {
                JitterBuffer buffer = entry.getValue();
                if (buffer.isExpired()) {
                    expiredSenders.add(entry.getKey());
                }
            }
            for (Integer senderId : expiredSenders) {
                senders.remove(senderId);
            }

            if (muted) {
                for (JitterBuffer buffer : activeBuffers) {
                    buffer.getFrame();
                }
                audioPlayer.play(silenceBytes);
            } else if (activeBuffers.isEmpty()) {
                audioPlayer.play(silenceBytes);
            } else {
                for (int i = 0; i < frameSize; i++) {
                    mixed[i] = 0;
                }

                boolean hasAudio = false;
                for (JitterBuffer buffer : activeBuffers) {
                    short[] frame = buffer.getFrame();
                    if (frame == null) continue;
                    hasAudio = true;
                    for (int i = 0; i < frameSize; i++) {
                        int sum = mixed[i] + frame[i];
                        if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                        else if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
                        mixed[i] = (short) sum;
                    }
                }

                if (hasAudio) {
                    audioPlayer.writeShorts(mixed);
                } else {
                    audioPlayer.play(silenceBytes);
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;
            long sleep = Constants.FRAME_MS - elapsed;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private short[] decodePcm(byte[] pcm) {
        short[] samples = new short[frameSize];
        int len = Math.min(pcm.length / 2, frameSize);
        for (int i = 0; i < len; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = (pcm[i * 2 + 1] & 0xFF) << 8;
            samples[i] = (short) (lo | hi);
        }
        return samples;
    }

    private static class JitterBuffer {
        private final TreeMap<Integer, short[]> buffer = new TreeMap<>();
        private Integer nextPlaySeq;
        private long lastPacketMs = System.currentTimeMillis();

        synchronized void enqueue(int sequence, short[] frame) {
            lastPacketMs = System.currentTimeMillis();

            if (nextPlaySeq != null) {
                if (sequence < nextPlaySeq || sequence > nextPlaySeq + JITTER_MAX_FUTURE) {
                    return;
                }
            }

            buffer.put(sequence, frame);

            if (nextPlaySeq == null && buffer.size() >= JITTER_PREFETCH) {
                nextPlaySeq = buffer.firstKey();
            }
        }

        synchronized short[] getFrame() {
            if (nextPlaySeq == null) {
                if (buffer.size() >= JITTER_PREFETCH) {
                    nextPlaySeq = buffer.firstKey();
                } else {
                    return null;
                }
            }

            short[] frame = buffer.remove(nextPlaySeq++);
            if (buffer.isEmpty()) {
                nextPlaySeq = null;
            }
            return frame;
        }

        synchronized boolean isExpired() {
            return System.currentTimeMillis() - lastPacketMs > JITTER_TIMEOUT_MS;
        }
    }
}
