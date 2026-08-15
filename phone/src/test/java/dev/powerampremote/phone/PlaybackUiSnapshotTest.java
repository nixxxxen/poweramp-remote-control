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
