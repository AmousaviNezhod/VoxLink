package com.example.sample.model;

public class AudioPacket {
    public final int senderId;
    public final int sequence;
    public final long receivedAt;
    public final byte[] payload;

    public AudioPacket(int senderId, int sequence, byte[] payload) {
        this.senderId = senderId;
        this.sequence = sequence;
        this.receivedAt = System.currentTimeMillis();
        this.payload = payload;
    }
}
