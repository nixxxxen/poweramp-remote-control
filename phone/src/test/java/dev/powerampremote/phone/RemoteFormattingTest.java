package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;
import java.util.Map;

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
    public void metadataUsesEnglishResourcesAndDecimalPoint() {
        RemoteMetadataFormatter formatter = englishFormatter();
        RemoteState state = stateAt(3, 2_976);

        assertEquals("FLAC · 24 bit · 44.1 kHz · 1411 kbps", formatter.audio(state));
        assertEquals("FLAC", formatter.codec(state));
        assertEquals("24 bit", formatter.bitDepth(state));
        assertEquals("44.1 kHz", formatter.sampleRate(state));
        assertEquals("1411 kbps", formatter.bitrate(state));
        // The historical API v1 name is Russian, but English presentation uses the raw category.
        assertEquals("Queue · 4 / 2976", formatter.source(state));
    }

    @Test
    public void metadataUsesRussianResourcesAndDecimalComma() {
        RemoteMetadataFormatter formatter = russianFormatter();
        RemoteState state = stateAt(3, 2_976);

        assertEquals("FLAC · 24 бит · 44,1 кГц · 1411 кбит/с", formatter.audio(state));
        assertEquals("24 бит", formatter.bitDepth(state));
        assertEquals("44,1 кГц", formatter.sampleRate(state));
        assertEquals("1411 кбит/с", formatter.bitrate(state));
        assertEquals("Очередь · 4 / 2976", formatter.source(state));
    }

    @Test
    public void bitratePresentationAcceptsBothPowerampRepresentations() {
        assertEquals("320 kbps", englishFormatter().formatBitRate(320));
        assertEquals("320 kbps", englishFormatter().formatBitRate(320_000));
        assertEquals("320 кбит/с", russianFormatter().formatBitRate(320));
        assertEquals("320 кбит/с", russianFormatter().formatBitRate(320_000));
        assertEquals(null, englishFormatter().formatBitRate(null));
    }

    @Test
    public void queuePositionIsPresentedAsOneBasedOnlyForQueue() {
        RemoteMetadataFormatter formatter = englishFormatter();
        assertEquals("Queue · 1 / 18", formatter.source(stateAt(0, 18)));
        assertEquals("Queue · 9 / 18", formatter.source(stateAt(8, 18)));
        assertEquals("Queue · 18 / 18", formatter.source(stateAt(17, 18)));
        assertEquals("Queue", formatter.source(stateAt(-1, 18)));
        assertEquals("Queue", formatter.source(stateAt(18, 18)));
        assertEquals("Queue", formatter.source(stateAt(1, 0)));
        assertEquals("Queue", formatter.source(stateAt(null, 18, 800)));
        assertEquals("Queue", formatter.source(stateAt(0, null, 800)));
        assertEquals("Other source · 7 / 18", formatter.source(stateAt(7, 18, 10)));
    }

    @Test
    public void leavingQueueRemovesQueueSpecificPositionConversion() {
        RemoteMetadataFormatter formatter = englishFormatter();
        assertEquals("Queue · 1 / 18", formatter.source(stateAt(0, 18)));
        assertEquals("Other source · 0 / 18", formatter.source(stateAt(0, 18, 10)));
    }

    private static RemoteMetadataFormatter englishFormatter() {
        return new RemoteMetadataFormatter(
                Locale.ENGLISH,
                "bit",
                "kHz",
                "MHz",
                "kbps",
                Map.of(800, "Queue"),
                "Other source"
        );
    }

    private static RemoteMetadataFormatter russianFormatter() {
        return new RemoteMetadataFormatter(
                Locale.forLanguageTag("ru"),
                "бит",
                "кГц",
                "МГц",
                "кбит/с",
                Map.of(800, "Очередь"),
                "Другой источник"
        );
    }

    private static RemoteState stateAt(int position, int size) {
        return stateAt(position, size, RemoteSourceCategory.QUEUE);
    }

    private static RemoteState stateAt(int position, int size, int sourceCategory) {
        return stateAt(
                Integer.valueOf(position), Integer.valueOf(size),
                Integer.valueOf(sourceCategory)
        );
    }

    private static RemoteState stateAt(
            Integer position,
            Integer size,
            Integer sourceCategory
    ) {
        return new RemoteState(
                1L, true, true,
                "Track", "Artist", "Album", null,
                1, "FLAC", "flac",
                24, 44_100, 1_411_200,
                sourceCategory, "Очередь", "content://queue",
                position, size, 180, 37,
                "playing", 5, true, false, true, 2
        );
    }
}
