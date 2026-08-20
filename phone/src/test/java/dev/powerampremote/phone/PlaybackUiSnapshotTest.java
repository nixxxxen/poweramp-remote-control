package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PlaybackUiSnapshotTest {
    @Test
    public void reboundUiReceivesExtrapolatedServicePosition() {
        PlaybackUiSnapshot anchor = PlaybackUiSnapshot.anchor(
                state("playing", 0, 180),
                1_000L
        );

        PlaybackUiSnapshot rebound = anchor.capturedAt(38_900L);

        assertEquals(37, rebound.positionSeconds);
        assertEquals(38_900L, rebound.capturedRealtimeMilliseconds);
    }

    @Test
    public void pauseFreezeAndDurationClampDoNotAdvance() {
        PlaybackUiSnapshot playing = PlaybackUiSnapshot.anchor(
                state("playing", 175, 180),
                1_000L
        );
        assertEquals(180, playing.capturedAt(20_000L).positionSeconds);

        PlaybackUiSnapshot frozen = playing.frozenAt(3_900L);
        assertEquals(177, frozen.capturedAt(90_000L).positionSeconds);

        PlaybackUiSnapshot paused = PlaybackUiSnapshot.anchor(
                state("paused", 42, 180),
                5_000L
        );
        assertEquals(42, paused.capturedAt(90_000L).positionSeconds);
    }

    @Test
    public void repeatedActivityRebindCapturesDoNotResetTheServiceAnchor() {
        PlaybackUiSnapshot serviceSnapshot = PlaybackUiSnapshot.anchor(
                state("playing", 12, 180),
                1_000L
        );

        PlaybackUiSnapshot firstActivity = serviceSnapshot.capturedAt(11_900L);
        PlaybackUiSnapshot secondActivity = serviceSnapshot.capturedAt(31_900L);

        assertEquals(22, firstActivity.positionSeconds);
        assertEquals(42, secondActivity.positionSeconds);
        assertEquals(31_900L, secondActivity.capturedRealtimeMilliseconds);
    }

    @Test
    public void delayedMainThreadDeliveryKeepsOriginalSocketReceiptAnchor() {
        PlaybackUiSnapshot received = PlaybackUiSnapshot.anchor(
                state("playing", 50, 180),
                10_000L
        );

        // The main looper handles the callback 2.75 seconds after the socket thread received it.
        PlaybackUiSnapshot delivered = received.capturedAt(12_750L);

        assertEquals(52, delivered.positionSeconds);
        assertEquals(52_750L, received.positionMillisecondsAt(12_750L));
        assertEquals(10_000L, received.confirmedRealtimeMilliseconds());

        PlaybackUiSnapshot frozen = received.frozenAt(12_750L);
        assertEquals(52_750L, frozen.positionMillisecondsAt(60_000L));
    }

    @Test
    public void pauseSeekTrackChangeAndReconnectCreateFreshConfirmedAnchors() {
        PlaybackUiSnapshot beforePause = PlaybackUiSnapshot.anchor(
                state("playing", 20, 180),
                1_000L
        );
        assertEquals(24, beforePause.capturedAt(5_900L).positionSeconds);

        PlaybackUiSnapshot paused = PlaybackUiSnapshot.anchor(
                state("paused", 25, 180),
                6_000L
        );
        assertEquals(25, paused.capturedAt(60_000L).positionSeconds);

        PlaybackUiSnapshot confirmedSeek = PlaybackUiSnapshot.anchor(
                state("playing", 90, 180),
                61_000L
        );
        assertEquals(93, confirmedSeek.capturedAt(64_800L).positionSeconds);

        PlaybackUiSnapshot changedTrack = PlaybackUiSnapshot.anchor(
                state("playing", 0, 240),
                65_000L
        );
        assertEquals(2, changedTrack.capturedAt(67_400L).positionSeconds);

        PlaybackUiSnapshot disconnected = changedTrack.frozenAt(68_000L);
        assertEquals(3, disconnected.capturedAt(90_000L).positionSeconds);
        PlaybackUiSnapshot reconnected = PlaybackUiSnapshot.anchor(
                state("playing", 27, 240),
                91_000L
        );
        assertEquals(29, reconnected.capturedAt(93_200L).positionSeconds);
    }

    private static RemoteState state(String playbackState, int position, int duration) {
        return new RemoteState(
                1L, true, true,
                "Track", "Artist", "Album", null,
                1, "FLAC", "flac",
                24, 96_000, 1_411_200,
                800, "Queue", "content://queue",
                0, 10, duration, position,
                playbackState, 5, true, false, true, 2
        );
    }
}
