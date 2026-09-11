package dev.powerampremote.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QueueRequestGateTest {
    @Test
    public void reloadRejectsAnOlderContinuationResponse() {
        QueueRequestGate gate = new QueueRequestGate();
        QueueRequestGate.Request continuation = gate.begin(
                7, "abcdefghijklmnopqrstuvwx"
        );

        gate.invalidate();
        QueueRequestGate.Request firstPage = gate.begin(7, null);

        assertFalse(gate.accepts(
                continuation, 7, "abcdefghijklmnopqrstuvwx"
        ));
        assertTrue(gate.accepts(firstPage, 7, null));
    }

    @Test
    public void connectionOrTokenChangeRejectsLatePage() {
        QueueRequestGate gate = new QueueRequestGate();
        QueueRequestGate.Request request = gate.begin(
                4, "abcdefghijklmnopqrstuvwx"
        );

        assertFalse(gate.accepts(request, 5, "abcdefghijklmnopqrstuvwx"));
        assertFalse(gate.accepts(request, 4, "ABCDEFGHIJKLMNOPQRSTUVWX"));
        assertTrue(gate.accepts(request, 4, "abcdefghijklmnopqrstuvwx"));
    }
}
