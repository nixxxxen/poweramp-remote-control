package dev.r4remote.poweramp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Locale;

final class LocalNetworkAddress {
    private LocalNetworkAddress() {
    }

    static String findIpv4Address() {
        try {
            String fallback = null;
            for (NetworkInterface network : Collections.list(
                    NetworkInterface.getNetworkInterfaces()
            )) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                String interfaceName = network.getName().toLowerCase(Locale.ROOT);
                boolean preferredLanInterface = interfaceName.startsWith("wlan")
                        || interfaceName.startsWith("wifi")
                        || interfaceName.startsWith("eth");
                boolean excludedInterface = interfaceName.startsWith("tun")
                        || interfaceName.startsWith("vpn")
                        || interfaceName.startsWith("ppp")
                        || interfaceName.startsWith("rmnet")
                        || interfaceName.startsWith("ccmni")
                        || interfaceName.startsWith("clat");
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()
                            || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    if (preferredLanInterface) {
                        return address.getHostAddress();
                    }
                    if (!excludedInterface && fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (SocketException exception) {
            return null;
        }
    }
}
