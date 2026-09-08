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

    @Test
    public void rejectsLateRepresentativeArtworkAfterCategoryRowWasReused() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        RepresentativeArtworkKey artist = RepresentativeArtworkKey.create(
                SERVER_ID, RepresentativeArtworkKey.TYPE_ARTIST, 3L
        );
        RepresentativeArtworkKey album = RepresentativeArtworkKey.create(
                SERVER_ID, RepresentativeArtworkKey.TYPE_ALBUM, 4L
        );
        LibraryArtworkBindingGate.Request artistRequest = gate.bind(artist);

        LibraryArtworkBindingGate.Request albumRequest = gate.bind(album);

        assertFalse(gate.accepts(artistRequest, artist));
        assertTrue(gate.accepts(albumRequest, album));
    }

    private static LibraryArtworkKey key(long trackId) {
        return LibraryArtworkKey.create(
                SERVER_ID, "/api/v1/library/artwork/tracks/" + trackId
        );
    }

    @Test
    public void keepsDisplayedImageOnSameRowRebindAfterMemoryCacheEviction() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey key = key(1);
        LibraryArtworkBindingGate.Request request = gate.bind(key, 1);
        assertTrue(gate.begin(request));
        assertTrue(gate.complete(request, key, true));

        gate.bind(key, 1);
        assertTrue(gate.artworkDisplayed());
        gate.bind(key, 2); // Same-Server reconnect does not erase a displayed image either.
        assertTrue(gate.artworkDisplayed());
        gate.bind(key(2), 2);
        assertFalse(gate.artworkDisplayed());
    }

    @Test
    public void resumeCanRestartRequestWhoseCallbackWasIgnoredWhileStopped() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey key = key(1);
        LibraryArtworkBindingGate.Request stopped = gate.bind(key, 1);
        assertTrue(gate.begin(stopped));
        assertFalse(gate.begin(gate.bind(key, 1)));

        LibraryArtworkBindingGate.Request resumed = gate.bind(key, 2);
        assertTrue(gate.begin(resumed));
        assertFalse(gate.complete(stopped, key, true));
        assertFalse(gate.artworkDisplayed());
        assertFalse(gate.begin(resumed)); // Old completion cannot clear the new pending request.
        assertTrue(gate.complete(resumed, key, true));
        assertTrue(gate.artworkDisplayed());
    }

    @Test
    public void recycledRowCanRequestOriginalImageAgainWithoutAcceptingOldDelivery() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey first = key(1);
        LibraryArtworkBindingGate.Request old = gate.bind(first);
        assertTrue(gate.begin(old));
        gate.bind(key(2));
        LibraryArtworkBindingGate.Request current = gate.bind(first);
        assertTrue(gate.begin(current));
        assertFalse(gate.complete(old, first, true));
        assertTrue(gate.complete(current, first, true));
    }

    @Test
    public void failedImageCanBeRequestedOnLaterBind() {
        LibraryArtworkBindingGate gate = new LibraryArtworkBindingGate();
        LibraryArtworkKey key = key(1);
        LibraryArtworkBindingGate.Request first = gate.bind(key);
        assertTrue(gate.begin(first));
        assertTrue(gate.complete(first, key, false));
        assertFalse(gate.artworkDisplayed());
        assertTrue(gate.begin(gate.bind(key)));
    }
}
