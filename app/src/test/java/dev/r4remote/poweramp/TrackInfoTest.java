package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TrackInfoTest {
    @Test
    public void artworkUsesRealTrackId() {
        TrackInfo track = new TrackInfo(12L, 34L, null, null, null, 0, 0);

        assertEquals(34L, track.albumArtId());
    }

    @Test
    public void playlistEntryIdIsNotUsedForArtwork() {
        TrackInfo track = new TrackInfo(12L, 0L, null, null, null, 0, 0);

        assertEquals(0L, track.albumArtId());
    }

    @Test
    public void extendedStateKeepsAudioSourceAndRating() {
        TrackInfo.AudioProperties audio = new TrackInfo.AudioProperties(
                PowerampContract.FileTypes.FLAC,
                "flac",
                96_000,
                24,
                2_304_000
        );
        TrackInfo.PlaybackSource source = new TrackInfo.PlaybackSource(
                PowerampContract.Categories.QUEUE,
                "content://com.maxmpz.audioplayer/queue",
                2,
                12
        );
        TrackInfo track = new TrackInfo(
                12L,
                34L,
                "Track",
                "Album",
                "Artist",
                180,
                9,
                5,
                audio,
                source
        );

        assertEquals(5, track.rating);
        assertEquals(96_000, track.audio.sampleRate);
        assertEquals(PowerampContract.Categories.QUEUE, track.source.category);
        assertEquals(12, track.source.listSize);
    }
}
