package dev.powerampremote.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class PlaybackStateStoreTest {
    @Test
    public void sameTrackMetadataRefreshKeepsAdvancedPlaybackPosition() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        TrackInfo initial = track(1L, 2L, 10, 0);
        store.setTrack(initial, 0L);
        store.setPlaybackState(PowerampContract.STATE_PLAYING, 10, 0L);

        TrackInfo refreshed = track(1L, 2L, 0, 5);
        store.setTrack(refreshed, 5_000L);

        RemotePlaybackState state = store.snapshot();
        assertSame(refreshed, state.track);
        assertEquals(15, state.positionAt(5_000L));
        assertEquals(5, state.track.rating);
    }

    @Test
    public void sameTrackRefreshDoesNotEraseKnownDuration() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        TrackInfo initial = track(1L, 2L, 0, 0);
        store.setTrack(initial, 0L);

        TrackInfo partial = new TrackInfo(
                1L,
                2L,
                "Track",
                "Album",
                "Artist",
                0,
                0,
                0,
                initial.audio,
                initial.source
        );
        store.setTrack(partial, 1_000L);

        assertEquals(180, store.snapshot().track.durationSeconds);
    }

    @Test
    public void positionRemainsUnavailableUntilPowerampProvidesIt() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        store.setTrack(track(1L, 2L, -1, 0), 0L);
        store.setPlaybackState(PowerampContract.STATE_PLAYING, -1, 0L);

        assertFalse(store.snapshot().positionAvailable);

        store.setPosition(0, 1_000L);

        assertTrue(store.snapshot().positionAvailable);
        assertEquals(0, store.snapshot().positionAt(1_000L));
    }

    @Test
    public void unavailablePowerampClearsAllRemotePlaybackState() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        store.setTrack(track(1L, 2L, 7, 5), 0L);
        store.setPlaybackState(PowerampContract.STATE_PLAYING, 7, 0L);
        store.setShuffleMode(PowerampContract.ShuffleModes.SONGS, 0L);
        store.setArtworkAvailable(2L, true, 0L);

        store.setPowerampAvailable(false, 1_000L);

        RemotePlaybackState state = store.snapshot();
        assertFalse(state.powerampAvailable);
        assertNull(state.track);
        assertEquals(PowerampContract.STATE_UNKNOWN, state.playbackState);
        assertEquals(0, state.positionAt(1_000L));
        assertEquals(-1, state.shuffleMode);
        assertEquals(0L, state.artworkId);
        assertFalse(state.artworkAvailable);
    }

    @Test
    public void staleArtworkResultDoesNotChangeStateOrRevision() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        store.setTrack(track(1L, 2L, 0, 0), 0L);
        RemotePlaybackState before = store.snapshot();

        store.setArtworkAvailable(999L, true, 100L);

        assertSame(before, store.snapshot());
    }

    @Test
    public void listenersReceiveMonotonicRevisionsAndCanBeRemoved() {
        PlaybackStateStore store = new PlaybackStateStore(0L);
        List<Long> revisions = new ArrayList<>();
        PlaybackStateStore.Listener listener = state -> revisions.add(state.revision);
        store.addListener(listener);

        store.setPowerampAvailable(true, 0L);
        store.setTrack(track(1L, 2L, 0, 0), 0L);
        store.setPosition(4, 0L);
        store.removeListener(listener);
        store.setShuffleMode(PowerampContract.ShuffleModes.SONGS, 0L);

        assertEquals(3, revisions.size());
        assertEquals(Long.valueOf(1L), revisions.get(0));
        assertEquals(Long.valueOf(2L), revisions.get(1));
        assertEquals(Long.valueOf(3L), revisions.get(2));
        assertTrue(store.snapshot().revision > revisions.get(2));
    }

    private static TrackInfo track(
            long id,
            long realId,
            int positionSeconds,
            int rating
    ) {
        return new TrackInfo(
                id,
                realId,
                "Track",
                "Album",
                "Artist",
                180,
                positionSeconds,
                rating,
                new TrackInfo.AudioProperties(
                        PowerampContract.FileTypes.FLAC,
                        "flac",
                        96_000,
                        24,
                        1_411_200
                ),
                new TrackInfo.PlaybackSource(
                        PowerampContract.Categories.QUEUE,
                        null,
                        0,
                        10
                )
        );
    }
}
