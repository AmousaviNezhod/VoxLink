package com.example.sample.model;

import java.util.concurrent.atomic.AtomicBoolean;

public class Member {
    public final int id;
    public volatile String username;
    public volatile boolean isHost;
    public final AtomicBoolean isSpeaking = new AtomicBoolean(false);
    public final AtomicBoolean isMuted = new AtomicBoolean(false);
    public volatile long lastSeenMs;
    public volatile String ipAddress;

    public Member(int id, String username, boolean isHost) {
        this.id = id;
        this.username = (username == null || username.isEmpty()) ? ("User " + (id % 1000)) : username;
        this.isHost = isHost;
        this.lastSeenMs = System.currentTimeMillis();
    }
}
