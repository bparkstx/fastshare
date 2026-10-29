package com.fastshare.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Works out which addresses the other phone can use to reach this one. */
final class Net {
    private Net() { }

    static final class Addr {
        final String ip;
        final String label;   // "Wi-Fi", "Hotspot", "USB tethering"...

        Addr(String ip, String label) {
            this.ip = ip;
            this.label = label;
        }
    }

    static final class Info {
        final boolean wifi;            // this phone is joined to a Wi-Fi network
        final List<Addr> addrs;

        Info(boolean wifi, List<Addr> addrs) {
            this.wifi = wifi;
            this.addrs = addrs;
        }
    }

    /** Tests can set this to avoid depending on the machine's real network. */
    static volatile Info testOverride;

    static Info snapshot(Context ctx, boolean hotspotOn) {
        Info forced = testOverride;
        if (forced != null) return forced;

        boolean wifi = false;
        String wifiIface = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifi = true;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) wifiIface = lp.getInterfaceName();
                }
            }
        } catch (Exception ignored) { }

        List<Addr> wifiList = new ArrayList<>();
        List<Addr> hotspotList = new ArrayList<>();
        List<Addr> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("v4-") || name.startsWith("p2p")
                        || name.startsWith("lo")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    boolean isWifi = name.equals(wifiIface) || (wifiIface == null && name.startsWith("wlan0"));
                    boolean apLike = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap");
                    // The phone's own hotspot always hands out addresses ending in .1 to itself.
                    if (apLike || (hotspotOn && ip.endsWith(".1"))) {
                        hotspotList.add(new Addr(ip, "Hotspot"));
                    } else if (isWifi) {
                        wifiList.add(new Addr(ip, "Wi-Fi"));
                    } else if (name.startsWith("rndis") || name.startsWith("usb")) {
                        other.add(new Addr(ip, "USB tethering"));
                    } else if (name.startsWith("eth")) {
                        other.add(new Addr(ip, "Ethernet"));
                    } else {
                        other.add(new Addr(ip, "Network"));
                    }
                }
            }
        } catch (Exception ignored) { }

        List<Addr> all = new ArrayList<>();
        if (hotspotOn) {
            all.addAll(hotspotList);
            all.addAll(wifiList);
        } else {
            all.addAll(wifiList);
            all.addAll(hotspotList);
        }
        all.addAll(other);
        return new Info(wifi, all);
    }
}
