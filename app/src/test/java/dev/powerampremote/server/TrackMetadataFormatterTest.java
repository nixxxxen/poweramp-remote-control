package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TrackMetadataFormatterTest {
    @Test
    public void formatsLosslessAudioProperties() {
        TrackInfo.AudioProperties audio = new TrackInfo.AudioProperties(
                PowerampContract.FileTypes.FLAC,
                "flac",
                44_100,
                24,
                1_411_200
        );

        assertEquals(
                "FLAC · 24 бит · 44,1 кГц · 1411 кбит/с",
                TrackMetadataFormatter.formatAudio(audio)
        );
    }

    @Test
    public void includesCodecWhenItDiffersFromContainer() {
        TrackInfo.AudioProperties audio = new TrackInfo.AudioProperties(
                PowerampContract.FileTypes.M4A,
                "alac",
                -1,
                -1,
                -1
        );

        assertEquals("M4A / ALAC", TrackMetadataFormatter.formatAudio(audio));
    }

    @Test
    public void formatsHumanFriendlySourcePosition() {
        TrackInfo.PlaybackSource source = new TrackInfo.PlaybackSource(
                PowerampContract.Categories.PLAYLISTS,
                null,
                0,
                10
        );

        assertEquals("Плейлист · 1 / 10", TrackMetadataFormatter.formatSource(source));
    }

    @Test
    public void hidesUnavailableMetadata() {
        assertEquals("", TrackMetadataFormatter.formatAudio(TrackInfo.AudioProperties.UNKNOWN));
        assertEquals("", TrackMetadataFormatter.formatSource(TrackInfo.PlaybackSource.UNKNOWN));
    }
}
