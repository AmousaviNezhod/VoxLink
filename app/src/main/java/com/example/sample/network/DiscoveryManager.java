package com.example.sample.network;

import android.content.Context;
import android.util.Log;

import com.example.sample.util.Constants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class DiscoveryManager {
    private static final String TAG = "DiscoveryManager";
    private static final String PROBE_PREFIX = "VoxLink-Probe:";
    private static final String HOST_PREFIX = "VoxLink-Host:";

    private final Context context;
    private final String groupName;
    private DiscoveryListener listener;
    private Thread hostThread;
    private Thread clientThread;
    private DatagramSocket hostSocket;
    private DatagramSocket clientSocket;
    private volatile boolean isRegistered = false;
    private volatile boolean isDiscovering = false;

    public interface DiscoveryListener {
        void onGroupFound(String hostAddress, int port);
        void onGroupLost();
        void onError(String message);
    }

    public DiscoveryManager(Context context, String groupName, DiscoveryListener listener) {
        this.context = context.getApplicationContext();
        this.groupName = (groupName == null || groupName.isEmpty()) ? "default" : groupName;
        this.listener = listener;
    }

    public void registerGroup() {
        if (isRegistered) stopAll();
        isRegistered = true;
        hostThread = new Thread(this::hostLoop);
        hostThread.start();
    }

    public void startDiscovery() {
        if (isDiscovering) return;
        isDiscovering = true;
        clientThread = new Thread(this::clientLoop);
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

    private void hostLoop() {
        try {
            hostSocket = new DatagramSocket(null);
            hostSocket.setReuseAddress(true);
            hostSocket.bind(new InetSocketAddress(Constants.DISCOVERY_PORT));
            hostSocket.setBroadcast(true);
            hostSocket.setSoTimeout(1000);

            Log.d(TAG, "Host discovery listening on port " + Constants.DISCOVERY_PORT);

            byte[] buf = new byte[256];
            long lastBeacon = 0;

            while (isRegistered && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                if (now - lastBeacon >= 2000) {
                    sendBeacon();
                    lastBeacon = now;
                }

                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    hostSocket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith(PROBE_PREFIX)) {
                        String probeGroup = msg.substring(PROBE_PREFIX.length());
                        if (groupName.equals(probeGroup)) {
                            String reply = HOST_PREFIX + Constants.UDP_PORT + ":" + groupName;
                            byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                            DatagramPacket replyPacket = new DatagramPacket(
                                    replyBytes, replyBytes.length, packet.getAddress(), packet.getPort());
                            hostSocket.send(replyPacket);
                            Log.d(TAG, "Replied to probe from " + packet.getAddress().getHostAddress() + " group=" + groupName);
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Host discovery error: " + e.getMessage());
        } finally {
            cleanupHost();
        }
    }

    private void sendBeacon() {
        if (hostSocket == null) return;
        try {
            InetAddress broadcastAddress = NetworkHelper.getBroadcastAddress(context);
            if (broadcastAddress == null) {
                broadcastAddress = InetAddress.getByName("255.255.255.255");
            }

            String message = HOST_PREFIX + Constants.UDP_PORT + ":" + groupName;
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, Constants.DISCOVERY_PORT);
            hostSocket.send(packet);
            Log.d(TAG, "Sent discovery beacon to " + broadcastAddress.getHostAddress());
        } catch (Exception e) {
            Log.e(TAG, "Beacon send error: " + e.getMessage());
        }
    }

    private void clientLoop() {
        try {
            clientSocket = new DatagramSocket(null);
            clientSocket.setReuseAddress(true);
            clientSocket.bind(new InetSocketAddress(0));
            clientSocket.setBroadcast(true);
            clientSocket.setSoTimeout(1000);

            sendProbe();

            byte[] buf = new byte[256];
            long start = System.currentTimeMillis();
            long lastProbe = start;

            while (isDiscovering && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                if (now - start > 10000) {
                    if (listener != null) listener.onError("Discovery timeout");
                    break;
                }

                if (now - lastProbe >= 1000) {
                    sendProbe();
                    lastProbe = now;
                }

                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    clientSocket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith(HOST_PREFIX)) {
                        String hostAddress = packet.getAddress().getHostAddress();
                        int voicePort = Constants.UDP_PORT;
                        String foundGroup = "";
                        try {
                            String payload = msg.substring(HOST_PREFIX.length());
                            int colon = payload.indexOf(':');
                            if (colon >= 0) {
                                voicePort = Integer.parseInt(payload.substring(0, colon));
                                foundGroup = payload.substring(colon + 1);
                            } else {
                                voicePort = Integer.parseInt(payload);
                            }
                        } catch (Exception ignored) {}

                        if (!groupName.equals(foundGroup)) {
                            continue;
                        }

                        Log.d(TAG, "Host found: " + hostAddress + ":" + voicePort + " group=" + foundGroup);
                        if (listener != null) {
                            listener.onGroupFound(hostAddress, voicePort);
                        }
                        break;
                    }
                } catch (SocketTimeoutException ignored) {
                    // Continue loop and resend probe if needed.
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
    }

    private void sendProbe() {
        if (clientSocket == null) return;
        try {
            InetAddress broadcastAddress = NetworkHelper.getBroadcastAddress(context);
            if (broadcastAddress == null) {
                broadcastAddress = InetAddress.getByName("255.255.255.255");
            }

            byte[] buffer = (PROBE_PREFIX + groupName).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, Constants.DISCOVERY_PORT);
            clientSocket.send(packet);
            Log.d(TAG, "Sent discovery probe");
        } catch (Exception e) {
            Log.e(TAG, "Probe send error: " + e.getMessage());
        }
    }

    private void cleanupHost() {
        isRegistered = false;
        if (hostThread != null) {
            hostThread.interrupt();
            hostThread = null;
        }
        if (hostSocket != null && !hostSocket.isClosed()) {
            hostSocket.close();
            hostSocket = null;
        }
    }

    private void cleanupClient() {
        isDiscovering = false;
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
        if (clientSocket != null && !clientSocket.isClosed()) {
            clientSocket.close();
            clientSocket = null;
        }
    }

    public boolean isDiscovering() {
        return isDiscovering;
    }

    public boolean isRegistered() {
        return isRegistered;
    }
}
