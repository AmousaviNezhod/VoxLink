package com.example.sample;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class NetworkHelper {

    private static final String TAG = "NetworkHelper";

    private static boolean isDesiredInterface(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("wlan") || n.contains("ap") || n.contains("eth") ||
               n.contains("ppp") || n.contains("wlo") || n.contains("wl");
    }

    public static NetworkInterface getLocalNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface fallback = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                try {
                    if (intf.isUp() && !intf.isLoopback() && intf.supportsMulticast()) {
                        List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                        for (InetAddress addr : addrs) {
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                if (isDesiredInterface(intf.getName())) {
                                    Log.d(TAG, "Selected interface: " + intf.getName());
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

    public static boolean isLocalNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return getLocalNetworkInterface() != null;
    }

    public static InetAddress getBroadcastAddress(Context context) {
        try {
            NetworkInterface intf = getLocalNetworkInterface();
            if (intf != null) {
                List<InterfaceAddress> addrs = intf.getInterfaceAddresses();
                for (InterfaceAddress addr : addrs) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null && !broadcast.isLoopbackAddress()) {
                        Log.d(TAG, "Broadcast address: " + broadcast.getHostAddress());
                        return broadcast;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting broadcast address: " + e.getMessage());
        }
        return null;
    }
}
