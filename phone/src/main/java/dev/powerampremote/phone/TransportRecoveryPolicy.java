package dev.powerampremote.phone;

/** Pure LAN-to-direct transport decisions used by network lifecycle callbacks. */
final class TransportRecoveryPolicy {
    private TransportRecoveryPolicy() {
    }

    static boolean shouldInvalidateEndpoint(
            DiscoveredServer.Transport transport,
            boolean lanNetworkAvailable
    ) {
        return transport == DiscoveredServer.Transport.LAN && !lanNetworkAvailable;
    }

    static long directFallbackDelayMilliseconds(
            boolean lanNetworkAvailable,
            long lanGraceDelayMilliseconds
    ) {
        return lanNetworkAvailable ? Math.max(0L, lanGraceDelayMilliseconds) : 0L;
    }
}
