package dev.powerampremote.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LibraryArtworkBindingGateTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";

    @Test
    public void rejectsLateResultAfterRowWasReused() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey firstKey = key(1);
        LibraryArtworkKey secondKey = key(2);
        LibraryArtworkBindingGate.Request first = gate.bind(firstKey);

        LibraryArtworkBindingGate.Request second = gate.bind(secondKey);

        assertFalse(gate.accepts(first, firstKey));
        assertTrue(gate.accepts(second, secondKey));
        assertFalse(gate.accepts(second, firstKey));
    }

    @Test
    public void sameIdentityCanShareOneInFlightResult() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey key = key(7);
        LibraryArtworkBindingGate.Request first = gate.bind(key);
        LibraryArtworkBindingGate.Request rebound = gate.bind(key);

        assertTrue(gate.accepts(first, key));
        assertTrue(gate.accepts(rebound, key));
    }

    private static LibraryArtworkKey key(long trackId) {
        return LibraryArtworkKey.create(
                SERVER_ID, "/api/v1/library/artwork/tracks/" + trackId
        );
    }
}
