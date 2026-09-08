package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportRecoveryPolicyTest {
    @Test
    public void infrastructureWifiAndEthernetAreLanButWifiDirectIsNot() {
        assertTrue(TransportRecoveryPolicy.isInfrastructureLan(true, false, false));
        assertTrue(TransportRecoveryPolicy.isInfrastructureLan(false, true, false));
        assertFalse(TransportRecoveryPolicy.isInfrastructureLan(true, false, true));
        assertFalse(TransportRecoveryPolicy.isInfrastructureLan(false, false, true));
    }

    @Test
    public void wifiDirectDoesNotKeepLanAvailableAfterInfrastructureWifiIsLost() {
        LanNetworkTracker<String> tracker = new LanNetworkTracker<>();

        tracker.update("wifi", true, false, false);
        tracker.update("p2p", true, false, true);
        assertTrue(tracker.hasLanNetwork());

        tracker.remove("wifi");
        assertFalse(tracker.hasLanNetwork());
    }

    @Test
    public void capabilityChangeCanReclassifyAnAlreadyKnownNetwork() {
        LanNetworkTracker<String> tracker = new LanNetworkTracker<>();

        tracker.update("network", true, false, false);
        assertTrue(tracker.hasLanNetwork());

        tracker.update("network", true, false, true);
        assertFalse(tracker.hasLanNetwork());
    }

    @Test
    public void lossOfLanInvalidatesOnlyALanEndpoint() {
        assertTrue(TransportRecoveryPolicy.shouldInvalidateEndpoint(
                DiscoveredServer.Transport.LAN,
                false
        ));
        assertFalse(TransportRecoveryPolicy.shouldInvalidateEndpoint(
                DiscoveredServer.Transport.LAN,
                true
        ));
        assertFalse(TransportRecoveryPolicy.shouldInvalidateEndpoint(
                DiscoveredServer.Transport.WIFI_DIRECT,
                false
        ));
    }

    @Test
    public void directFallbackIsImmediateWithoutALanCapableNetwork() {
        assertEquals(0L, TransportRecoveryPolicy.directFallbackDelayMilliseconds(false, 8_000L));
        assertEquals(8_000L, TransportRecoveryPolicy.directFallbackDelayMilliseconds(true, 8_000L));
        assertEquals(0L, TransportRecoveryPolicy.networkRecoveryDelayMilliseconds(
                true,
                false,
                500L
        ));
        assertEquals(500L, TransportRecoveryPolicy.networkRecoveryDelayMilliseconds(
                false,
                true,
                500L
        ));
    }
}
