package com.example.sample;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class DiscoveryManager {
    private static final String TAG = "DiscoveryManager";
    private static final int DISCOVERY_PORT = 8888;
    private static final String MAGIC_WORD = "VoxLink-Host: ";

    private DiscoveryListener listener;
    private Thread hostThread;
    private Thread clientThread;
    private DatagramSocket hostSocket;
    private DatagramSocket clientSocket;
    private boolean isRegistered = false;
    private boolean isDiscovering = false;
    private Context context;

    public interface DiscoveryListener {
        void onGroupFound(String hostAddress, int port);
        void onGroupLost();
        void onError(String message);
    }

    public DiscoveryManager(Context context, DiscoveryListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void registerGroup() {
        if (isRegistered) stopAll();
        isRegistered = true;

        hostThread = new Thread(() -> {
            try {
                hostSocket = new DatagramSocket();
                hostSocket.setBroadcast(true);

                String message = MAGIC_WORD + Constants.UDP_PORT;
                byte[] buffer = message.getBytes();

                // پیدا کردن broadcast آدرس صحیح
                InetAddress broadcastAddress = getBroadcastAddress();
                if (broadcastAddress == null) {
                    broadcastAddress = InetAddress.getByName("255.255.255.255");
                }

                Log.d(TAG, "UDP Broadcast beacon started to: " + broadcastAddress.getHostAddress());

                while (isRegistered && !Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(
                            buffer, buffer.length, broadcastAddress, DISCOVERY_PORT);

                    hostSocket.send(packet);
                    Log.d(TAG, "Sent live beacon to network...");

                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Host beacon error: " + e.getMessage());
            } finally {
                cleanupHost();
            }
        });
        hostThread.start();
    }

    public void startDiscovery() {
        if (isDiscovering) return;
        isDiscovering = true;

        clientThread = new Thread(() -> {
            try {
                clientSocket = new DatagramSocket(null);
                clientSocket.setReuseAddress(true);
                clientSocket.bind(new InetSocketAddress(DISCOVERY_PORT));

                byte[] buffer = new byte[1024];
                Log.d(TAG, "UDP Discovery listening on port " + DISCOVERY_PORT);

                while (isDiscovering && !Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                    clientSocket.receive(packet);

                    String data = new String(packet.getData(), 0, packet.getLength());

                    if (data.startsWith(MAGIC_WORD)) {
                        String hostAddress = packet.getAddress().getHostAddress();

                        int voicePort = Constants.UDP_PORT;
                        try {
                            String portStr = data.substring(MAGIC_WORD.length());
                            voicePort = Integer.parseInt(portStr);
                        } catch (Exception ignored) {}

                        Log.d(TAG, "Host detected via UDP! IP: " + hostAddress + " Port: " + voicePort);

                        if (listener != null) {
                            listener.onGroupFound(hostAddress, Constants.UDP_PORT);
                        }

                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Client discovery error: " + e.getMessage());
                if (isDiscovering && listener != null) {
                    listener.onError(e.getMessage());
                }
            } finally {
                cleanupClient();
            }
        });
        clientThread.start();
    }

    public void stopDiscovery() {
        isDiscovering = false;
        cleanupClient();
    }

    public void stopAll() {
        isRegistered = false;
        isDiscovering = false;
        cleanupHost();
        cleanupClient();
    }

    private void cleanupHost() {
        if (hostThread != null) {
            hostThread.interrupt();
            hostThread = null;
        }
        if (hostSocket != null && !hostSocket.isClosed()) {
            hostSocket.close();
            hostSocket = null;
        }
        isRegistered = false;
    }

    private void cleanupClient() {
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
        if (clientSocket != null && !clientSocket.isClosed()) {
            clientSocket.close();
            clientSocket = null;
        }
        isDiscovering = false;
    }

    public boolean isDiscovering() {
        return isDiscovering;
    }

    /**
     * پیدا کردن broadcast آدرس صحیح برای شبکه فعلی.
     * DhcpInfo بازگشتی اندروید معمولاً little-endian است، بنابراین معکوس می‌شود
     * و سپس بایت‌ها به صورت big-endian (مورد نیاز InetAddress) استخراج می‌شوند.
     */
    private InetAddress getBroadcastAddress() {
        try {
            // روش اول: استفاده از WifiManager
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                    int ip = Integer.reverseBytes(dhcp.ipAddress);
                    int netmask = Integer.reverseBytes(dhcp.netmask);
                    int broadcast = (ip & netmask) | (~netmask);
                    byte[] quads = new byte[4];
                    quads[0] = (byte) ((broadcast >> 24) & 0xFF);
                    quads[1] = (byte) ((broadcast >> 16) & 0xFF);
                    quads[2] = (byte) ((broadcast >> 8) & 0xFF);
                    quads[3] = (byte) (broadcast & 0xFF);
                    InetAddress addr = InetAddress.getByAddress(quads);
                    if (!addr.isLoopbackAddress()) {
                        Log.d(TAG, "Found broadcast address via WifiManager: " + addr.getHostAddress());
                        return addr;
                    }
                }
            }

            // روش دوم: استفاده از NetworkInterface (برای هات‌اسپات/WiFi Direct)
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isUp() && !intf.isLoopback()) {
                    List<java.net.InterfaceAddress> addrs = intf.getInterfaceAddresses();
                    for (java.net.InterfaceAddress addr : addrs) {
                        InetAddress broadcast = addr.getBroadcast();
                        if (broadcast != null && !broadcast.isLoopbackAddress()) {
                            Log.d(TAG, "Found broadcast address via NetworkInterface: " + broadcast.getHostAddress() + " on " + intf.getDisplayName());
                            return broadcast;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting broadcast address: " + e.getMessage());
        }
        return null;
    }
}