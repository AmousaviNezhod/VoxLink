package com.example.sample.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.example.sample.util.Constants;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;

public class UdpReceiver {
    private static final String TAG = "UdpReceiver";

    private MulticastSocket socket;
    private InetAddress groupAddress;
    private NetworkInterface netInterface;
    private Thread receiveThread;
    private volatile boolean isRunning = false;

    private AudioReceiveListener audioReceiveListener;
    private AudioPacketListener audioPacketListener;
    private ControlPacketListener controlPacketListener;

    private WifiManager.MulticastLock multicastLock;
    private Context context;

    public interface AudioReceiveListener {
        void onAudioReceived(int senderId, byte[] audioData);
    }

    public interface AudioPacketListener {
        void onAudioPacket(int senderId, int sequence, byte[] payload);
    }

    public interface ControlPacketListener {
        void onControlPacket(int senderId, byte[] payload);
    }

    public UdpReceiver(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    public UdpReceiver(AudioReceiveListener listener, Context context) {
        this.audioReceiveListener = listener;
        this.context = context == null ? null : context.getApplicationContext();
    }

    public void setAudioPacketListener(AudioPacketListener l) {
        this.audioPacketListener = l;
    }

    public void setControlPacketListener(ControlPacketListener l) {
        this.controlPacketListener = l;
    }

    public boolean start(int mySenderId) {
        try {
            netInterface = NetworkHelper.getLocalNetworkInterface(context);

            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Constants.UDP_PORT));
            if (netInterface != null) {
                socket.setNetworkInterface(netInterface);
            }

            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("VoxLinkMulticastLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.d(TAG, "Multicast Lock acquired successfully.");
            }

            groupAddress = InetAddress.getByName(Constants.MULTICAST_GROUP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && netInterface != null) {
                socket.joinGroup(new InetSocketAddress(groupAddress, Constants.UDP_PORT), netInterface);
                Log.d(TAG, "Joined multicast group on interface: " + netInterface.getDisplayName());
            } else {
                socket.joinGroup(groupAddress);
            }

            isRunning = true;
            receiveThread = new Thread(() -> receiveLoop(mySenderId));
            receiveThread.setDaemon(true);
            receiveThread.start();

            Log.d(TAG, "UdpReceiver started successfully.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error starting UdpReceiver: " + e.getMessage());
            return false;
        }
    }

    private void receiveLoop(int mySenderId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        byte[] buffer = new byte[Constants.MAX_PACKET_SIZE + Constants.PACKET_HEADER_SIZE];
        while (isRunning) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                int length = packet.getLength();
                if (length < Constants.PACKET_HEADER_SIZE) continue;

                byte[] data = packet.getData();

                int senderId = ((data[0] & 0xFF) << 24) |
                        ((data[1] & 0xFF) << 16) |
                        ((data[2] & 0xFF) << 8) |
                        (data[3] & 0xFF);

                if (senderId == mySenderId) continue;

                int sequence = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
                int payloadLength = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

                if (payloadLength < 0 || payloadLength > length - Constants.PACKET_HEADER_SIZE) continue;

                byte[] payload = new byte[payloadLength];
                System.arraycopy(data, Constants.PACKET_HEADER_SIZE, payload, 0, payloadLength);

                if (isControlPayload(payloadLength)) {
                    if (controlPacketListener != null) {
                        controlPacketListener.onControlPacket(senderId, payload);
                    }
                } else {
                    if (audioPacketListener != null) {
                        audioPacketListener.onAudioPacket(senderId, sequence, payload);
                    }
                }

                if (audioReceiveListener != null) {
                    audioReceiveListener.onAudioReceived(senderId, payload);
                }

            } catch (SocketTimeoutException e) {
                // expected on timeout
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Error in receive loop: " + e.getMessage());
                }
            }
        }
    }

    private boolean isControlPayload(int payloadLength) {
        return payloadLength == 1 || payloadLength == Constants.MEMBER_CONTROL_PACKET_SIZE;
    }

    public void stop() {
        isRunning = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        try {
            if (socket != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && netInterface != null && groupAddress != null) {
                    try {
                        socket.leaveGroup(new InetSocketAddress(groupAddress, Constants.UDP_PORT), netInterface);
                    } catch (Exception ignored) {}
                } else if (groupAddress != null) {
                    try {
                        socket.leaveGroup(groupAddress);
                    } catch (Exception ignored) {}
                }
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping UdpReceiver: " + e.getMessage());
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            Log.d(TAG, "Multicast Lock released.");
        }
    }
}
