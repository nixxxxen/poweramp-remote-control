package dev.powerampremote.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SearchRequestGateTest {
    @Test
    public void newerQueryRejectsOlderResult() {
        SearchRequestGate gate = new SearchRequestGate();
        SearchRequestGate.Request old = gate.begin("first", 10);
        SearchRequestGate.Request current = gate.begin("second", 10);

        assertFalse(gate.accepts(old, "second", 10));
        assertTrue(gate.accepts(current, "second", 10));
    }

    @Test
    public void connectionGenerationChangeRejectsInFlightResult() {
        SearchRequestGate gate = new SearchRequestGate();
        SearchRequestGate.Request request = gate.begin("rare title", 4);

        assertFalse(gate.accepts(request, "rare title", 5));
        assertTrue(gate.accepts(request, "rare title", 4));
        gate.invalidate();
        assertFalse(gate.accepts(request, "rare title", 4));
    }

    @Test
    public void clearInvalidatesTheActiveRequestGeneration() {
        SearchRequestGate gate = new SearchRequestGate();
        SearchRequestGate.Request active = gate.begin("Oblivion", 9);

        gate.invalidate();

        assertFalse(gate.accepts(active, "", 9));
        assertFalse(gate.accepts(active, "Oblivion", 9));
    }
}
