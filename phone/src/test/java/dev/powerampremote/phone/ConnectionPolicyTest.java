package dev.powerampremote.phone;

import org.junit.Test;

import static dev.powerampremote.phone.DiscoveredServer.Transport.LAN;
import static dev.powerampremote.phone.DiscoveredServer.Transport.WIFI_DIRECT;
import static org.junit.Assert.*;

public final class ConnectionPolicyTest {
    @Test public void persistedChoicesDefaultToExistingLanPreferenceAndCannotPreferDisabledDirect() {
        assertEquals(ConnectionPolicy.LAN_PREFERRED, ConnectionPolicy.fromStored(null));
        assertEquals(ConnectionPolicy.LAN_PREFERRED, ConnectionPolicy.fromStored("unknown"));
        assertEquals(ConnectionPolicy.LAN_ONLY, ConnectionPolicy.fromSwitches(false, true));
        for (ConnectionPolicy policy : ConnectionPolicy.values()) {
            assertEquals(policy, ConnectionPolicy.fromStored(policy.name()));
        }
        TransportSelectionPolicy state = new TransportSelectionPolicy(ConnectionPolicy.LAN_ONLY);
        assertFalse(state.maySelect(WIFI_DIRECT, null, false));
        assertFalse(state.beginManual(WIFI_DIRECT));
    }

    @Test public void directPreferenceFallsBackInABoundedWindowAndKeepsHealthyFallback() {
        TransportSelectionPolicy state = new TransportSelectionPolicy(ConnectionPolicy.DIRECT_PREFERRED);
        assertFalse(state.maySelect(LAN, null, false));
        assertTrue(state.maySelect(WIFI_DIRECT, null, false));
        assertEquals(60_000L, TransportSelectionPolicy.SELECTION_TIMEOUT_MS);
        state.allowFallback();
        assertTrue(state.maySelect(LAN, null, false));
        state.confirmed(LAN);
        assertFalse(state.maySelect(WIFI_DIRECT, LAN, true));
        assertFalse(state.maySelect(LAN, WIFI_DIRECT, true));
    }

    @Test public void manualLanOverridesDirectPreferenceUntilLossOrPolicyChange() {
        TransportSelectionPolicy state = new TransportSelectionPolicy(ConnectionPolicy.DIRECT_PREFERRED);
        assertTrue(state.beginManual(LAN));
        assertFalse(state.beginManual(WIFI_DIRECT));
        assertTrue(state.maySelect(LAN, WIFI_DIRECT, true));
        state.confirmed(LAN);
        assertFalse(state.switching());
        assertEquals(LAN, state.preferred());
        assertFalse(state.maySelect(WIFI_DIRECT, LAN, true));
        state.unavailable(LAN);
        assertEquals(WIFI_DIRECT, state.preferred());
        assertTrue(state.beginManual(LAN));
        state.changePolicy(ConnectionPolicy.LAN_ONLY);
        assertFalse(state.switching());
        assertEquals(LAN, state.preferred());
        assertFalse(state.maySelect(WIFI_DIRECT, null, false));
    }

    @Test public void manualDirectPreventsAutomaticLanPromotionUntilUnavailable() {
        TransportSelectionPolicy state = new TransportSelectionPolicy(ConnectionPolicy.LAN_PREFERRED);
        assertTrue(state.beginManual(WIFI_DIRECT));
        state.confirmed(WIFI_DIRECT);
        assertFalse(state.maySelect(LAN, WIFI_DIRECT, true));
        state.unavailable(WIFI_DIRECT);
        assertTrue(state.maySelect(LAN, null, false));
        assertTrue(TransportSelectionPolicy.exhausted(2, 0L));
        assertTrue(TransportSelectionPolicy.exhausted(1, 15_000L));
        assertFalse(TransportSelectionPolicy.exhausted(1, 14_999L));
    }

    @Test public void policyChangeCanProbePreferredPathWithoutDroppingHealthyCurrentPath() {
        TransportSelectionPolicy state = new TransportSelectionPolicy(ConnectionPolicy.LAN_PREFERRED);
        state.changePolicy(ConnectionPolicy.DIRECT_PREFERRED);
        assertTrue(state.beginRecovery(0L));
        assertTrue(state.maySelect(WIFI_DIRECT, LAN, true));
        state.allowFallback();
        assertFalse(state.maySelect(WIFI_DIRECT, LAN, true));
    }

    @Test public void staleProbeCannotReplaceOrDisconnectConfirmedSocket() {
        ConnectionAttemptGate gate = new ConnectionAttemptGate();
        long lan = gate.begin();
        assertTrue(gate.confirm(lan));
        long oldDirect = gate.begin();
        long newDirect = gate.begin();
        assertFalse(gate.confirm(oldDirect));
        assertTrue(gate.confirmed(lan));
        gate.cancelPending();
        assertFalse(gate.accepts(newDirect));
        assertTrue(gate.confirmed(lan));
        long next = gate.begin();
        gate.loseConfirmed();
        assertTrue(gate.pending(next));
        assertTrue(gate.confirm(next));
        assertFalse(gate.accepts(lan));
        gate.cancelAll();
        assertFalse(gate.accepts(next));
    }

    @Test public void callbacksCannotPostponeImmediateOrEarlierRecovery() {
        RecoveryDeadline deadline = new RecoveryDeadline();
        assertTrue(deadline.schedule(0L, 8_000L));
        assertFalse(deadline.schedule(1_000L, 8_000L));
        assertTrue(deadline.schedule(2_000L, 0L));
        assertFalse(deadline.schedule(2_001L, 500L));
        deadline.clear();
        assertTrue(deadline.schedule(3_000L, 500L));
    }

    @Test public void logsAreProcessMemoryBoundedAndOnlyCollectedWhenEnabled() {
        ConnectionLogBuffer buffer = new ConnectionLogBuffer();
        buffer.add("ignored");
        assertTrue(buffer.snapshot().isEmpty());
        buffer.setEnabled(true);
        for (int i = 0; i < 101; i++) buffer.add("event=" + i);
        assertEquals(100, buffer.snapshot().size());
        assertEquals("event=1", buffer.snapshot().get(0));
        buffer.add("single\nline\rentry");
        assertEquals("single line entry", buffer.snapshot().get(99));
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
        buffer.add("last");
        buffer.setEnabled(false);
        assertTrue(buffer.snapshot().isEmpty());
        buffer.setEnabled(true);
        assertTrue(buffer.snapshot().isEmpty());
    }
}
