package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RemoteFormattingTest {
    @Test
    public void timeFormattingSupportsLongTracks() {
        assertEquals("0:00", TimeFormatter.formatSeconds(-1));
        assertEquals("3:07", TimeFormatter.formatSeconds(187));
        assertEquals("1:02:03", TimeFormatter.formatSeconds(3_723));
    }

    @Test
    public void websocketAcceptMatchesRfcExample() throws Exception {
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                RemoteWebSocket.expectedAccept("dGhlIHNhbXBsZSBub25jZQ==")
        );
    }

    @Test
    public void metadataUsesHumanReadableBitrateAndListPosition() {
        RemoteState state = new RemoteState(
                1L, true, true,
                "Track", "Artist", "Album", null,
                1, "FLAC", "flac",
                24, 96_000, 1_411_200,
                800, "Очередь", "content://queue",
                3, 2_976, 180, 37,
                "playing", 5, true, false, true, 2
        );

        assertEquals("FLAC · 24 бит · 96 кГц · 1411 кбит/с",
                RemoteMetadataFormatter.audio(state));
        assertEquals("Очередь · 3 / 2976", RemoteMetadataFormatter.source(state));
    }

    @Test
    public void bitratePresentationAcceptsBothPowerampRepresentations() {
        assertEquals("320 кбит/с", RemoteMetadataFormatter.formatBitRate(320));
        assertEquals("320 кбит/с", RemoteMetadataFormatter.formatBitRate(320_000));
        assertEquals(null, RemoteMetadataFormatter.formatBitRate(null));
    }

    @Test
    public void listPositionKeepsTheUnverifiedPowerampIndexWithoutAnOffset() {
        assertEquals("Очередь · 0 / 10", RemoteMetadataFormatter.source(stateAt(0, 10)));
        assertEquals("Очередь · 10 / 10", RemoteMetadataFormatter.source(stateAt(10, 10)));
    }

    private static RemoteState stateAt(int position, int size) {
        return new RemoteState(
                1L, true, true,
                "Track", "Artist", "Album", null,
                1, "FLAC", "flac",
                24, 96_000, 320_000,
                800, "Очередь", "content://queue",
                position, size, 180, 37,
                "playing", 5, true, false, true, 2
        );
    }
}
