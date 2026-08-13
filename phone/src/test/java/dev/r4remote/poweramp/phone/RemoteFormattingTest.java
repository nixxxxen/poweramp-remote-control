package dev.r4remote.poweramp.phone;

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
}
