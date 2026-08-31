package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArtworkSwipeGestureTest {
    @Test
    public void leftCommitsNextAndRightCommitsPrevious() {
        ArtworkSwipeGesture left = gesture();
        ArtworkSwipeGesture.Finish next = left.finish(-30f, 1f, 1, true);
        assertTrue(next.committed);
        assertEquals(ArtworkNavigationCoordinator.Direction.NEXT, next.direction);

        ArtworkSwipeGesture right = gesture();
        ArtworkSwipeGesture.Finish previous = right.finish(30f, -1f, 1, true);
        assertTrue(previous.committed);
        assertEquals(ArtworkNavigationCoordinator.Direction.PREVIOUS, previous.direction);
    }

    @Test
    public void commitThresholdIsCentralizedAndShortSwipeReturnsWithoutCommand() {
        assertEquals(24f, ArtworkSwipeGesture.commitThresholdPixels(100f, 5f), 0.001f);
        ArtworkSwipeGesture shortSwipe = gesture();

        ArtworkSwipeGesture.Move move = shortSwipe.move(23f, 0f, 1, true);
        assertTrue(move.trackingHorizontal);
        assertFalse(move.thresholdReached);
        assertFalse(shortSwipe.finish(23f, 0f, 1, true).committed);
    }

    @Test
    public void verticalDiagonalCancelledAndMultiTouchNeverCommit() {
        ArtworkSwipeGesture vertical = gesture();
        assertTrue(vertical.move(8f, 18f, 1, true).cancelled);
        assertFalse(vertical.finish(40f, 18f, 1, true).committed);

        ArtworkSwipeGesture diagonal = gesture();
        assertTrue(diagonal.move(18f, 17f, 1, true).cancelled);
        assertFalse(diagonal.finish(40f, 17f, 1, true).committed);

        ArtworkSwipeGesture multiTouch = gesture();
        assertTrue(multiTouch.move(30f, 0f, 2, true).cancelled);
        assertFalse(multiTouch.finish(30f, 0f, 1, true).committed);
    }

    @Test
    public void thresholdHapticOccursOnlyOnceEvenAfterRecrossing() {
        ArtworkSwipeGesture swipe = gesture();
        assertTrue(swipe.move(25f, 0f, 1, true).performHaptic);
        assertFalse(swipe.move(10f, 0f, 1, true).performHaptic);
        assertFalse(swipe.move(30f, 0f, 1, true).performHaptic);
        assertFalse(swipe.finish(30f, 0f, 1, true).performHaptic);
    }

    @Test
    public void confirmedGestureCanProduceOnlyOneCommand() {
        ArtworkSwipeGesture swipe = gesture();
        assertTrue(swipe.finish(-30f, 0f, 1, true).committed);
        assertFalse(swipe.finish(-30f, 0f, 1, true).committed);
    }

    @Test
    public void cancellationOrUnavailableControlsPreventConfirmation() {
        ArtworkSwipeGesture cancelled = gesture();
        cancelled.move(16f, 0f, 1, true);
        cancelled.cancel();
        assertFalse(cancelled.finish(40f, 0f, 1, true).committed);

        ArtworkSwipeGesture unavailable = gesture();
        assertTrue(unavailable.move(40f, 0f, 1, false).cancelled);
        assertFalse(unavailable.finish(40f, 0f, 1, false).committed);
    }

    private static ArtworkSwipeGesture gesture() {
        return new ArtworkSwipeGesture(0f, 0f, 100f, 5f);
    }
}
