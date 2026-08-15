package com.example.sample;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class UdpReceiver {
    private static final String TAG = "UdpReceiver";
    private MulticastSocket socket;
    private InetAddress groupAddress;
    private NetworkInterface netInterface;
    private Thread receiveThread;
    private boolean isRunning = false;
    private AudioReceiveListener listener;

    private WifiManager.MulticastLock multicastLock;
    private Context context;

    public interface AudioReceiveListener {
        void onAudioReceived(int senderId, byte[] audioData);
    }

    public UdpReceiver(AudioReceiveListener listener, Context context) {
        this.listener = listener;
        this.context = context.getApplicationContext();
    }

    public boolean start(int mySenderId) {
        try {
            socket = new MulticastSocket(Constants.UDP_PORT);
            groupAddress = InetAddress.getByName(Constants.MULTICAST_GROUP);

            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("VoxLinkMulticastLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
                Log.d(TAG, "Multicast Lock acquired successfully.");
            }

            this.netInterface = getMulticastNetworkInterface();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this.netInterface != null) {
                socket.joinGroup(new InetSocketAddress(groupAddress, Constants.UDP_PORT), this.netInterface);
                Log.d(TAG, "Joined multicast group on interface: " + this.netInterface.getDisplayName());
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

    private NetworkInterface getMulticastNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface fallback = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                try {
                    if (intf.isUp() && !intf.isLoopback() && intf.supportsMulticast()) {
                        List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                        for (InetAddress addr : addrs) {
                            if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                                String name = intf.getName() == null ? "" : intf.getName().toLowerCase();
                                if (name.contains("wlan") || name.contains("eth") || name.contains("ap")) {
                                    return intf;
                                }
                                if (fallback == null) {
                                    fallback = intf;
                                }
                            }
                        }
                    }
                } catch (SocketException ignored) {}
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "Error selecting network interface: " + e.getMessage());
            return null;
        }
    }

    private void receiveLoop(int mySenderId) {
        byte[] buffer = new byte[Constants.MAX_PACKET_SIZE + Constants.PACKET_HEADER_SIZE];
        while (isRunning) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] data = packet.getData();
                int length = packet.getLength();

                if (length < Constants.PACKET_HEADER_SIZE) continue;

                int senderId = ((data[0] & 0xFF) << 24) |
                        ((data[1] & 0xFF) << 16) |
                        ((data[2] & 0xFF) << 8) |
                        (data[3] & 0xFF);

                if (senderId == mySenderId) continue;

                int audioLength = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                if (audioLength <= 0 || audioLength > length - Constants.PACKET_HEADER_SIZE) continue;

                byte[] audioData = new byte[audioLength];
                System.arraycopy(data, Constants.PACKET_HEADER_SIZE, audioData, 0, audioLength);

                if (listener != null) {
                    listener.onAudioReceived(senderId, audioData);
                }

            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Error in receive loop: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (socket != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && netInterface != null) {
                    socket.leaveGroup(new InetSocketAddress(groupAddress, Constants.UDP_PORT), netInterface);
                } else {
                    socket.leaveGroup(groupAddress);
                }
                socket.close();
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                Log.d(TAG, "Multicast Lock released.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping UdpReceiver: " + e.getMessage());
        }
    }
}
