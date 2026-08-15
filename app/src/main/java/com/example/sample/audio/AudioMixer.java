package com.example.sample.audio;

/**
 * Per-sender jitter buffer + mixer + playback thread.
 * This skeleton defines the API used by VoxLinkService; the audio child will implement it.
 */
public class AudioMixer {

    private final AudioPlayer audioPlayer;
    private final int frameSize;

    public AudioMixer(AudioPlayer audioPlayer, int frameSize) {
        this.audioPlayer = audioPlayer;
        this.frameSize = frameSize;
    }

    public void start() {
        // TODO: start playback thread
    }

    public void stop() {
        // TODO: stop playback thread and release resources
    }

    public void setMuted(boolean muted) {
        // TODO: silence own playback or skip mixing when muted
    }

    public void enqueue(int senderId, int sequence, byte[] pcm) {
        // TODO: store in per-sender jitter buffer keyed by sequence
    }
}
