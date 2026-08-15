package com.example.sample.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
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

    private static boolean isUsableInterface(NetworkInterface ni) {
        if (ni == null) return false;
        try {
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) return false;
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return true;
            }
        } catch (SocketException ignored) {}
        return false;
    }

    private static NetworkInterface getActiveNetworkInterface(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network network = cm.getActiveNetwork();
                    if (network != null) {
                        LinkProperties link = cm.getLinkProperties(network);
                        if (link != null) {
                            String ifName = link.getInterfaceName();
                            if (ifName != null && !ifName.isEmpty()) {
                                NetworkInterface ni = NetworkInterface.getByName(ifName);
                                if (isUsableInterface(ni)) {
                                    Log.d(TAG, "Active interface: " + ifName);
                                    return ni;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading active network interface: " + e.getMessage());
            }
        }
        return null;
    }

    private static NetworkInterface getFallbackNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                try {
                    if (isUsableInterface(intf)) {
                        if (isDesiredInterface(intf.getName())) {
                            Log.d(TAG, "Fallback desired interface: " + intf.getName());
                            return intf;
                        }
                        if (fallback == null) {
                            fallback = intf;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (fallback != null) {
                Log.d(TAG, "Fallback interface: " + fallback.getName());
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "Error enumerating network interfaces: " + e.getMessage());
            return null;
        }
    }

    public static NetworkInterface getLocalNetworkInterface(Context context) {
        NetworkInterface active = getActiveNetworkInterface(context);
        if (active != null) return active;
        return getFallbackNetworkInterface();
    }

    public static NetworkInterface getLocalNetworkInterface() {
        return getLocalNetworkInterface((Context) null);
    }

    public static InetAddress getLocalInetAddress(Context context) {
        NetworkInterface ni = getLocalNetworkInterface(context);
        if (ni == null) return null;
        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                return addr;
            }
        }
        return null;
    }

    public static InetAddress getBroadcastAddress(Context context) {
        NetworkInterface ni = getLocalNetworkInterface(context);
        if (ni == null) return null;
        try {
            List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
            if (addrs != null) {
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

    public static boolean isLocalNetworkAvailable(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking network availability: " + e.getMessage());
        }
        return getLocalNetworkInterface(context) != null;
    }
}
