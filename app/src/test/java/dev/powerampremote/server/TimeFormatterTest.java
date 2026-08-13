package dev.powerampremote.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TimeFormatterTest {
    @Test
    public void formatsNegativeAsZero() {
        assertEquals("0:00", TimeFormatter.formatSeconds(-3));
    }

    @Test
    public void formatsMinutesAndSeconds() {
        assertEquals("3:07", TimeFormatter.formatSeconds(187));
    }

    @Test
    public void formatsHours() {
        assertEquals("1:02:03", TimeFormatter.formatSeconds(3_723));
    }
}

