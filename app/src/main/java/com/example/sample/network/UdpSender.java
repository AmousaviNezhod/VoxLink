package com.example.sample.network;

import android.content.Context;
import android.util.Log;

import com.example.sample.util.Constants;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpSender {
    private static final String TAG = "UdpSender";

    private MulticastSocket socket;
    private InetAddress groupAddress;
    private volatile boolean isReady = false;
    private final int senderId;
    private final Context context;
    private final AtomicInteger audioSequence = new AtomicInteger(0);

    public UdpSender() {
        this(null);
    }

    public UdpSender(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        this.senderId = generateSenderId();
    }

    private static int generateSenderId() {
        int id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        if (id <= 0) {
            id = 1;
        }
        return id;
    }

    public boolean prepare() {
        try {
            NetworkInterface netInterface = NetworkHelper.getLocalNetworkInterface(context);
            if (netInterface == null) {
                Log.w(TAG, "No usable network interface found; multicast may fail");
            }

            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            if (netInterface != null) {
                socket.setNetworkInterface(netInterface);
            }
            socket.setTimeToLive(1);

            groupAddress = InetAddress.getByName(Constants.MULTICAST_GROUP);
            isReady = true;

            Log.d(TAG, "UdpSender ready. Sender ID: " + senderId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error preparing UdpSender: " + e.getMessage());
            return false;
        }
    }

    public void sendAudio(byte[] pcm) {
        if (!isReady || socket == null || pcm == null) return;
        int seq = audioSequence.getAndIncrement() & 0xFFFF;
        sendWithSequence(pcm, seq);
    }

    public void sendControl(byte[] data) {
        if (!isReady || socket == null || data == null) return;
        sendWithSequence(data, 0);
    }

    /**
     * Backward-compatible alias used by {@code VoiceRoomActivity}.
     * Small payloads are treated as control; larger payloads as audio.
     */
    public void send(byte[] data) {
        if (data == null) return;
        if (data.length <= Constants.MEMBER_CONTROL_PACKET_SIZE) {
            sendControl(data);
        } else {
            sendAudio(data);
        }
    }

    private void sendWithSequence(byte[] data, int seq) {
        try {
            byte[] packet = buildPacket(data, seq & 0xFFFF);
            DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, groupAddress, Constants.UDP_PORT);
            socket.send(datagramPacket);
        } catch (Exception e) {
            Log.e(TAG, "Error sending packet: " + e.getMessage());
        }
    }

    private byte[] buildPacket(byte[] data, int sequence) {
        byte[] packet = new byte[Constants.PACKET_HEADER_SIZE + data.length];
        packet[0] = (byte) (senderId >> 24);
        packet[1] = (byte) (senderId >> 16);
        packet[2] = (byte) (senderId >> 8);
        packet[3] = (byte) senderId;
        packet[4] = (byte) ((sequence >> 8) & 0xFF);
        packet[5] = (byte) (sequence & 0xFF);
        packet[6] = (byte) ((data.length >> 8) & 0xFF);
        packet[7] = (byte) (data.length & 0xFF);
        System.arraycopy(data, 0, packet, Constants.PACKET_HEADER_SIZE, data.length);
        return packet;
    }

    public void close() {
        isReady = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public int getSenderId() {
        return senderId;
    }

    public boolean isReady() {
        return isReady;
    }
}
