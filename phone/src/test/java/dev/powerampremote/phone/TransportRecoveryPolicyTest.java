package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportRecoveryPolicyTest {
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
    }
}
