package com.example.sample.network;

import android.util.Log;

import com.example.sample.util.Constants;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.UUID;

public class UdpSender {
    private static final String TAG = "UdpSender";
    private DatagramSocket socket;
    private InetAddress groupAddress;
    private boolean isReady = false;
    private int senderId;
    private short sequenceNum;

    public UdpSender() {
        senderId = Math.abs(UUID.randomUUID().hashCode());
        if (senderId == 0) senderId = 1;
        sequenceNum = 0;
    }

    public boolean prepare() {
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            groupAddress = InetAddress.getByName(Constants.MULTICAST_GROUP);
            isReady = true;
            Log.d(TAG, "UdpSender ready. Sender ID: " + senderId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error preparing UdpSender: " + e.getMessage());
            return false;
        }
    }

    public void send(byte[] data) {
        if (!isReady || socket == null) return;
        try {
            byte[] packet = buildPacket(data);
            DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, groupAddress, Constants.UDP_PORT);
            socket.send(datagramPacket);
            sequenceNum++;
        } catch (Exception e) {
            Log.e(TAG, "Error sending: " + e.getMessage());
        }
    }

    private byte[] buildPacket(byte[] data) {
        byte[] packet = new byte[Constants.PACKET_HEADER_SIZE + data.length];
        packet[0] = (byte)(senderId >> 24);
        packet[1] = (byte)(senderId >> 16);
        packet[2] = (byte)(senderId >> 8);
        packet[3] = (byte)(senderId);
        packet[4] = (byte)(sequenceNum >> 8);
        packet[5] = (byte)(sequenceNum);
        packet[6] = (byte)(data.length >> 8);
        packet[7] = (byte)(data.length);
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
