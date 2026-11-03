package dev.powerampremote.phone;

import org.junit.Test;

import static dev.powerampremote.phone.DiscoveredServer.Transport.LAN;
import static dev.powerampremote.phone.DiscoveredServer.Transport.WIFI_DIRECT;
import static org.junit.Assert.*;

public final class DirectPreferredRecoveryTest {
    private static final long WINDOW = TransportSelectionPolicy.SELECTION_TIMEOUT_MS;

    @Test public void confirmedDirectLossStartsFreshPriorityDespiteEarlierFallback() {
        TransportSelectionPolicy state = directPreferred();
        state.allowFallback();
        state.confirmed(WIFI_DIRECT);
        state.unavailable(WIFI_DIRECT);

        assertTrue(state.beginRecovery(1_000L));
        assertTrue(state.maySelect(WIFI_DIRECT, null, false));
        assertFalse(state.maySelect(LAN, null, false));
        state.unavailable(WIFI_DIRECT);
        assertFalse(state.maySelect(LAN, null, false));
    }

    @Test public void retainedGroupRetriesApiWithinWindowInsteadOfEarlyCooldownOrLan() {
        TransportSelectionPolicy state = directPreferred();
        state.confirmed(WIFI_DIRECT);
        state.unavailable(WIFI_DIRECT);
        assertTrue(state.beginRecovery(0L));

        // Both the two-failure and 15-second stale-path budgets used to reject this endpoint.
        assertTrue(TransportSelectionPolicy.exhausted(2, 1_000L));
        assertTrue(TransportSelectionPolicy.exhausted(1, 15_000L));
        for (long now : new long[]{1_000L, 15_000L, WINDOW - 1L}) {
            state.unavailable(WIFI_DIRECT);
            assertTrue(state.shouldRetryDirectApi(true, now));
            assertFalse(state.maySelect(LAN, null, false));
            assertFalse(state.beginRecovery(now));
        }
        // Server returns through the retained group; confirmation ends this cycle.
        state.confirmed(WIFI_DIRECT);
        assertFalse(state.shouldRetryDirectApi(true, WINDOW));
        assertFalse(state.maySelect(LAN, WIFI_DIRECT, true));
    }

    @Test public void groupLossUsesDiscoveryButDoesNotResetPriorityOrAllowImmediateLan() {
        TransportSelectionPolicy state = directPreferred();
        state.confirmed(WIFI_DIRECT);
        state.unavailable(WIFI_DIRECT);
        assertTrue(state.beginRecovery(0L));
        // API fails first, then the group-loss callback arrives in the same cycle.
        state.unavailable(WIFI_DIRECT);
        assertFalse(state.beginRecovery(5_000L));
        assertFalse(state.shouldRetryDirectApi(false, 5_000L));
        assertTrue(state.maySelect(WIFI_DIRECT, null, false));
        assertFalse(state.maySelect(LAN, null, false));
        assertFalse(state.prioritizesDirect(WINDOW));
    }

    @Test public void repeatedFailuresAndCallbacksCannotPostponeFallbackDeadline() {
        TransportSelectionPolicy state = directPreferred();
        assertTrue(state.beginRecovery(0L));
        for (long now : new long[]{1L, 15_000L, WINDOW - 1L, WINDOW, WINDOW + 1L}) {
            state.unavailable(WIFI_DIRECT);
            assertFalse(state.beginRecovery(now));
        }
        // Only the existing controller timeout releases fallback, not a path-loss callback.
        state.allowFallback();
        assertTrue(state.maySelect(LAN, null, false));
        assertFalse(state.shouldRetryDirectApi(true, WINDOW));
    }

    @Test public void genuinelyUnavailableDirectAllowsLanAndNeverDisplacesHealthyFallback() {
        TransportSelectionPolicy state = directPreferred();
        assertTrue(state.beginRecovery(0L));
        state.allowFallback(); // Window elapsed, or a terminal P2P failure was confirmed.
        assertTrue(state.maySelect(LAN, null, false));
        state.confirmed(LAN);
        for (long now : new long[]{WINDOW, WINDOW * 2L, WINDOW * 10L}) {
            assertFalse(state.prioritizesDirect(now));
            assertFalse(state.maySelect(WIFI_DIRECT, LAN, true));
        }
    }

    @Test public void disconnectedRediscoveryAfterTimeoutAndCooldownStartsNewBoundedCycle() {
        TransportSelectionPolicy state = directPreferred();
        state.allowFallback(); // State left by an older attempt must not leak into this one.
        assertTrue(state.beginRecovery(0L));
        state.allowFallback();
        long serverReturns = WINDOW + TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS;
        assertTrue(state.beginRecovery(serverReturns));
        assertTrue(state.shouldRetryDirectApi(true, serverReturns));
        assertFalse(state.maySelect(LAN, null, false));
        assertFalse(state.beginRecovery(serverReturns + WINDOW - 1L));
        state.allowFallback();
        assertTrue(state.maySelect(LAN, null, false));
    }

    @Test public void lossOfManualLanPriorityReturnsToSavedDirectPolicy() {
        TransportSelectionPolicy state = directPreferred();
        assertTrue(state.beginManual(LAN));
        state.confirmed(LAN);
        assertFalse(state.beginRecovery(0L));
        assertFalse(state.maySelect(WIFI_DIRECT, LAN, true));
        state.unavailable(LAN);
        assertTrue(state.beginRecovery(1_000L));
        assertEquals(WIFI_DIRECT, state.preferred());
        assertFalse(state.maySelect(LAN, null, false));
    }

    @Test public void losingHealthyLanFallbackStartsNewSavedPreferenceCycle() {
        TransportSelectionPolicy state = directPreferred();
        assertTrue(state.beginRecovery(0L));
        state.allowFallback();
        state.confirmed(LAN);
        state.unavailable(LAN);
        assertTrue(state.beginRecovery(WINDOW * 3L));
        assertTrue(state.maySelect(WIFI_DIRECT, null, false));
        assertFalse(state.maySelect(LAN, null, false));
    }

    @Test public void lanModesAndPendingManualChoiceDoNotStartAutomaticDirectWindow() {
        for (ConnectionPolicy policy : new ConnectionPolicy[]{
                ConnectionPolicy.LAN_ONLY, ConnectionPolicy.LAN_PREFERRED}) {
            TransportSelectionPolicy state = new TransportSelectionPolicy(policy);
            state.confirmed(LAN);
            state.unavailable(LAN);
            assertFalse(state.beginRecovery(0L));
            assertTrue(state.maySelect(LAN, null, false));
            assertEquals(policy.allowsDirect(), state.maySelect(WIFI_DIRECT, null, false));
        }
        TransportSelectionPolicy state = directPreferred();
        assertTrue(state.beginManual(LAN));
        state.unavailable(WIFI_DIRECT);
        assertFalse(state.beginRecovery(0L));
        assertTrue(state.maySelect(LAN, null, false));
        assertFalse(state.maySelect(WIFI_DIRECT, null, false));
    }

    private static TransportSelectionPolicy directPreferred() {
        return new TransportSelectionPolicy(ConnectionPolicy.DIRECT_PREFERRED);
    }
}
