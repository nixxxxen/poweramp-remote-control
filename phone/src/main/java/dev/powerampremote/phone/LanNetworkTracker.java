package dev.powerampremote.phone;

import java.util.HashSet;
import java.util.Set;

/** Tracks only infrastructure LAN networks across NetworkCapabilities updates. */
final class LanNetworkTracker<T> {
    private final Set<T> networks = new HashSet<>();

    boolean update(
            T network,
            boolean hasWifiTransport,
            boolean hasEthernetTransport,
            boolean hasWifiP2pCapability
    ) {
        boolean wasLan = networks.contains(network);
        boolean isLan = TransportRecoveryPolicy.isInfrastructureLan(
                hasWifiTransport,
                hasEthernetTransport,
                hasWifiP2pCapability
        );
        if (isLan) {
            networks.add(network);
        } else {
            networks.remove(network);
        }
        return wasLan != isLan;
    }

    boolean remove(T network) {
        return networks.remove(network);
    }

    boolean hasLanNetwork() {
        return !networks.isEmpty();
    }

    void clear() {
        networks.clear();
    }
}
