package dev.powerampremote.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WifiDirectRecoveryPolicyTest {
    @Test
    public void discoveryFailuresUseBoundedBackoffAndSuccessResetsIt() {
        WifiDirectRecoveryPolicy policy = new WifiDirectRecoveryPolicy();

        assertEquals(1_000L, policy.nextDiscoveryRetryDelayMilliseconds());
        assertEquals(2_000L, policy.nextDiscoveryRetryDelayMilliseconds());
        policy.onDiscoveryStarted();
        assertEquals(1_000L, policy.nextDiscoveryRetryDelayMilliseconds());
    }

    @Test
    public void connectionFailureAutoRetriesOnlyAfterAConfirmedGroup() {
        WifiDirectRecoveryPolicy policy = new WifiDirectRecoveryPolicy();

        assertFalse(policy.shouldAutomaticallyRetryConnection());
        policy.onGroupConnected();
        assertTrue(policy.shouldAutomaticallyRetryConnection());
    }
}
