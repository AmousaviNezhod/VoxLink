package com.example.sample.util;

public class Constants {

    public static final int UDP_PORT = 50005;
    public static final int DISCOVERY_PORT = 8888;
    public static final String MULTICAST_GROUP = "239.255.42.99";

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SIZE = 320;
    public static final int FRAME_MS = FRAME_SIZE * 1000 / SAMPLE_RATE; // 20 ms

    public static final int PACKET_HEADER_SIZE = 8;
    public static final int MAX_PACKET_SIZE = 1024;

    public static final int MEMBER_TIMEOUT_MS = 5000;
    public static final int MEMBER_CHECK_INTERVAL_MS = 2000;
    public static final int PING_INTERVAL_MS = 2000;

    // Control packet type bytes (shared between network/service and UI)
    public static final byte PACKET_PING_CLIENT = 0x01;
    public static final byte PACKET_PING_HOST = 0x02;
    public static final byte PACKET_ROOM_DESTROYED = 0x03;
    public static final byte PACKET_CLIENT_LEAVE = 0x04;
    public static final byte PACKET_MEMBER_JOINED = 0x05;
    public static final byte PACKET_MEMBER_LEFT = 0x06;
    public static final byte PACKET_MEMBER_INFO = 0x07;
    public static final byte PACKET_MUTE_MEMBER = 0x08;
    public static final byte PACKET_KICK_MEMBER = 0x09;
    public static final byte PACKET_HOST_TRANSFER = 0x0A;
    public static final byte PACKET_ROOM_LOCKED = 0x0B;

    // Packet size for id-only control packets: 1 byte type + 4 bytes id
    public static final int MEMBER_CONTROL_PACKET_SIZE = 5;
}
