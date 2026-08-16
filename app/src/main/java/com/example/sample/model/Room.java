package com.example.sample.model;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    public final String roomId;
    public final boolean isHost;
    public volatile int hostId = -1;
    public volatile int mySenderId = -1;
    public final ConcurrentHashMap<Integer, Member> members = new ConcurrentHashMap<>();
    public volatile boolean isLocked = false;
    private final Set<Integer> bannedIds = ConcurrentHashMap.newKeySet();

    public Room(String roomId, boolean isHost) {
        this.roomId = roomId == null || roomId.isEmpty() ? "default" : roomId;
        this.isHost = isHost;
    }

    public Member getOrCreate(int id, String defaultName) {
        Member m = members.get(id);
        if (m == null) {
            Member newbie = new Member(id, defaultName, false);
            Member existing = members.putIfAbsent(id, newbie);
            m = existing != null ? existing : newbie;
        }
        if (m.username == null || m.username.isEmpty()) {
            m.username = defaultName;
        }
        m.lastSeenMs = System.currentTimeMillis();
        return m;
    }

    public boolean isBanned(int id) {
        return bannedIds.contains(id);
    }

    public void ban(int id) {
        bannedIds.add(id);
    }

    public void unban(int id) {
        bannedIds.remove(id);
    }

    public Set<Integer> getBannedIds() {
        return Collections.unmodifiableSet(bannedIds);
    }
}
