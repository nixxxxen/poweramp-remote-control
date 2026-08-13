package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteSessionSnapshotTest {
    @Test
    public void mapsRemoteMetadataPlaybackAndTimelineWithoutLocalAudioState() {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(state(1L, "playing", 37), true);

        assertTrue(snapshot.hasTrack);
        assertTrue(snapshot.controlsAvailable);
        assertTrue(snapshot.playing);
        assertTrue(snapshot.seekAvailable);
        assertEquals("Track", snapshot.title);
        assertEquals("Artist", snapshot.artist);
        assertEquals("Album", snapshot.album);
        assertEquals(180_000L, snapshot.durationMilliseconds);
        assertEquals(37_000L, snapshot.positionMilliseconds);
        assertEquals(7, snapshot.volume);
        assertEquals(15, snapshot.volumeMax);
        assertTrue(snapshot.volumeAvailable);
        assertTrue(snapshot.volumeControlAvailable);
    }

    @Test
    public void disconnectStopsExtrapolationAndDisablesCommandsButKeepsMetadata() {
        RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.from(state(1L, "playing", 37), false);

        assertTrue(snapshot.hasTrack);
        assertFalse(snapshot.controlsAvailable);
        assertFalse(snapshot.playing);
        assertFalse(snapshot.seekAvailable);
        assertTrue(snapshot.volumeAvailable);
        assertFalse(snapshot.volumeControlAvailable);
        assertEquals("Track", snapshot.title);
    }

    @Test
    public void clampsPositionAndKeepsMediaIdentityStableAcrossRevisions() {
        RemoteSessionSnapshot first = RemoteSessionSnapshot.from(state(1L, "paused", 999), true);
        RemoteSessionSnapshot second = RemoteSessionSnapshot.from(state(2L, "paused", 40), true);

        assertEquals(180_000L, first.positionMilliseconds);
        assertEquals(first.mediaId, second.mediaId);
    }

    private static RemoteState state(long revision, String playbackState, int positionSeconds) {
        return new RemoteState(
                revision,
                true,
                true,
                " Track ",
                "Artist",
                "Album",
                "/api/v1/artwork",
                1,
                "FLAC",
                "flac",
                24,
                96_000,
                1_411_200,
                800,
                "Queue",
                "content://queue",
                0,
                10,
                180,
                positionSeconds,
                playbackState,
                5,
                true,
                false,
                true,
                2,
                7,
                15,
                true
        );
    }
}
