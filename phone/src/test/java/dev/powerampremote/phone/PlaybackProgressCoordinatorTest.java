package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackProgressCoordinatorTest {
    @Test
    public void playingUsesOriginalMonotonicSnapshotAnchorAndPausedDoesNotMove() {
        PlaybackProgressCoordinator playing = new PlaybackProgressCoordinator();
        PlaybackUiSnapshot playingSnapshot = snapshot("playing", 10, 120, 1_000L);
        playing.onSnapshot(playingSnapshot, 3_500L, 120_000L, true, true);
        assertEquals(13_000L, playing.positionMillisecondsAt(4_000L));

        PlaybackProgressCoordinator paused = new PlaybackProgressCoordinator();
        paused.onSnapshot(snapshot("paused", 37, 120, 5_000L),
                90_000L, 120_000L, true, true);
        assertEquals(37_000L, paused.positionMillisecondsAt(190_000L));
    }

    @Test
    public void durationClampsExtrapolatedPosition() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 119, 120, 1_000L),
                1_000L, 120_000L, true, true);

        assertEquals(120_000L, coordinator.positionMillisecondsAt(20_000L));
    }

    @Test
    public void smallDifferenceSmoothsAndConvergesToAuthoritativeAnchor() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, true);

        PlaybackProgressCoordinator.SnapshotApplication result = coordinator.onSnapshot(
                snapshot("playing", 10, 180, 2_000L),
                2_400L,
                180_000L,
                false,
                true
        );

        assertEquals(PlaybackProgressCoordinator.SnapshotApplication.SMOOTHED, result);
        assertEquals(11_400L, coordinator.positionMillisecondsAt(2_400L));
        long end = 2_400L + PlaybackProgressCoordinator.CORRECTION_DURATION_MILLISECONDS;
        assertEquals(10_000L + (end - 2_000L), coordinator.positionMillisecondsAt(end));
        assertFalse(coordinator.correctionActive());
    }

    @Test
    public void largeDiscontinuityAndTrackChangeApplyImmediately() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, true);
        PlaybackProgressCoordinator.SnapshotApplication result = coordinator.onSnapshot(
                snapshot("playing", 70, 180, 2_000L),
                2_000L,
                180_000L,
                false,
                true
        );
        assertEquals(PlaybackProgressCoordinator.SnapshotApplication.IMMEDIATE, result);
        assertEquals(70_000L, coordinator.positionMillisecondsAt(2_000L));

        coordinator.onTrackChanged(240_000L);
        assertEquals(0L, coordinator.positionMillisecondsAt(50_000L));
        coordinator.onSnapshot(snapshot("playing", 2, 240, 50_000L),
                50_000L, 240_000L, true, true);
        assertEquals(2_000L, coordinator.positionMillisecondsAt(50_000L));
    }

    @Test
    public void trackChangeClearsAnActiveCorrectionFromTheOldTrack() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, true);
        coordinator.onSnapshot(snapshot("playing", 10, 180, 2_000L),
                2_400L, 180_000L, false, true);
        assertTrue(coordinator.correctionActive());

        coordinator.onTrackChanged(240_000L);

        assertFalse(coordinator.correctionActive());
        assertFalse(coordinator.hasPendingSeek());
        assertEquals(0L, coordinator.positionMillisecondsAt(2_500L));
    }

    @Test
    public void pauseResumeUsesConfirmedPositionRatherThanCallbackTime() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 20, 180, 1_000L),
                3_750L, 180_000L, true, true);
        assertEquals(22_750L, coordinator.positionMillisecondsAt(3_750L));

        PlaybackUiSnapshot paused = snapshot("paused", 23, 180, 4_000L).capturedAt(7_000L);
        coordinator.onSnapshot(paused, 7_000L, 180_000L, false, true);
        assertEquals(23_000L, coordinator.positionMillisecondsAt(30_000L));

        PlaybackUiSnapshot resumed = snapshot("playing", 23, 180, 8_000L)
                .capturedAt(9_250L);
        coordinator.onSnapshot(resumed, 9_250L, 180_000L, false, true);
        assertEquals(25_000L, coordinator.positionMillisecondsAt(10_000L));
    }

    @Test
    public void draggingBlocksAutomaticProgressAndPendingSeekIgnoresOldSnapshot() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, true);
        coordinator.startDragging(55_500L);
        coordinator.updateDragPosition(61_250L);

        assertEquals(61_250L, coordinator.positionMillisecondsAt(20_000L));
        assertFalse(coordinator.shouldRunContinuousUpdates(true, true, true));

        assertEquals(61, coordinator.commitSeek(61_250L, 20_000L, true));
        PlaybackProgressCoordinator.SnapshotApplication old = coordinator.onSnapshot(
                snapshot("playing", 12, 180, 20_100L),
                20_100L,
                180_000L,
                false,
                true
        );
        assertEquals(PlaybackProgressCoordinator.SnapshotApplication.PENDING_IGNORED, old);
        assertEquals(61_350L, coordinator.positionMillisecondsAt(20_100L));
    }

    @Test
    public void seekConfirmationReturnsToAuthoritativeAnchor() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, true);
        coordinator.startDragging(80_000L);
        coordinator.commitSeek(80_000L, 5_000L, true);

        PlaybackUiSnapshot confirmation = snapshot("playing", 80, 180, 5_200L);
        PlaybackProgressCoordinator.SnapshotApplication result = coordinator.onSnapshot(
                confirmation, 5_300L, 180_000L, false, true
        );

        assertFalse(coordinator.hasPendingSeek());
        assertTrue(result == PlaybackProgressCoordinator.SnapshotApplication.IMMEDIATE
                || result == PlaybackProgressCoordinator.SnapshotApplication.SMOOTHED);
        long end = 5_300L + PlaybackProgressCoordinator.CORRECTION_DURATION_MILLISECONDS;
        assertEquals(80_000L + (end - 5_200L), coordinator.positionMillisecondsAt(end));
    }

    @Test
    public void timeoutAndCommandFailureReturnToConfirmedPosition() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("paused", 20, 180, 1_000L),
                1_000L, 180_000L, true, true);
        coordinator.startDragging(90_000L);
        coordinator.commitSeek(90_000L, 2_000L, false);
        assertEquals(90_000L, coordinator.positionMillisecondsAt(3_000L));
        assertEquals(20_000L, coordinator.positionMillisecondsAt(4_001L));
        assertFalse(coordinator.hasPendingSeek());

        coordinator.startDragging(70_000L);
        coordinator.commitSeek(70_000L, 5_000L, false);
        coordinator.onCommandFailure(5_100L);
        assertEquals(20_000L, coordinator.positionMillisecondsAt(5_100L));
    }

    @Test
    public void lifecycleAndReducedMotionPoliciesKeepProgressAccurate() {
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator();
        coordinator.onSnapshot(snapshot("playing", 10, 180, 1_000L),
                1_000L, 180_000L, true, false);

        assertFalse(coordinator.shouldRunContinuousUpdates(false, true, true));
        assertTrue(coordinator.shouldRunContinuousUpdates(true, true, true));
        assertEquals(PlaybackProgressCoordinator.SnapshotApplication.IMMEDIATE,
                coordinator.onSnapshot(snapshot("playing", 12, 180, 3_000L),
                        3_000L, 180_000L, false, false));
        assertEquals(12_500L, coordinator.positionMillisecondsAt(3_500L));

        coordinator.freezeAt(3_500L);
        assertFalse(coordinator.shouldRunContinuousUpdates(true, true, true));
        assertEquals(12_500L, coordinator.positionMillisecondsAt(30_000L));
    }

    @Test
    public void elapsedLabelPolicyChangesOnlyOnWholeSecondBoundary() {
        PlaybackProgressCoordinator.DisplayedSecondTracker tracker =
                new PlaybackProgressCoordinator.DisplayedSecondTracker();

        assertTrue(tracker.shouldUpdate(1_001L));
        assertFalse(tracker.shouldUpdate(1_999L));
        assertTrue(tracker.shouldUpdate(2_000L));
        assertFalse(tracker.shouldUpdate(2_999L));
    }

    private static PlaybackUiSnapshot snapshot(
            String playbackState,
            int position,
            int duration,
            long receivedRealtimeMilliseconds
    ) {
        return PlaybackUiSnapshot.anchor(
                new RemoteState(
                        1L, true, true,
                        "Track", "Artist", "Album", null,
                        1, "FLAC", "flac",
                        24, 96_000, 1_411_200,
                        800, "Queue", "content://queue",
                        0, 10, duration, position,
                        playbackState, 0, false, false, false, 0
                ),
                receivedRealtimeMilliseconds
        );
    }
}
