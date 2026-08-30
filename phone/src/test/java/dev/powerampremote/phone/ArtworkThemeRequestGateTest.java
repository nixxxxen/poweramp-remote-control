package dev.powerampremote.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArtworkThemeRequestGateTest {
    @Test
    public void acceptsOnlyCurrentArtworkGeneration() {
        ArtworkThemeRequestGate gate = new ArtworkThemeRequestGate();
        ArtworkThemeRequestGate.Request first = gate.begin("track-one");
        assertTrue(gate.accepts(first));

        ArtworkThemeRequestGate.Request second = gate.begin("track-two");
        assertFalse(gate.accepts(first));
        assertTrue(gate.accepts(second));
    }

    @Test
    public void lateResultsFromRapidTrackChangesStayRejected() {
        ArtworkThemeRequestGate gate = new ArtworkThemeRequestGate();
        ArtworkThemeRequestGate.Request first = gate.begin("track-one");
        ArtworkThemeRequestGate.Request second = gate.begin("track-two");
        ArtworkThemeRequestGate.Request third = gate.begin("track-three");

        assertFalse(gate.accepts(first));
        assertFalse(gate.accepts(second));
        assertTrue(gate.accepts(third));

        ArtworkThemeRequestGate.Request repeatedThird = gate.begin("track-three");
        assertTrue(gate.accepts(third));
        assertTrue(gate.accepts(repeatedThird));
    }
}
