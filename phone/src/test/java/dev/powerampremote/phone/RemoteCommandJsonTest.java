package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class RemoteCommandJsonTest {
    @Test
    public void emitsExactApiV1Bodies() {
        assertEquals("{\"action\":\"play\"}", RemoteCommandJson.play());
        assertEquals("{\"action\":\"pause\"}", RemoteCommandJson.pause());
        assertEquals("{\"action\":\"previous\"}", RemoteCommandJson.previous());
        assertEquals("{\"action\":\"next\"}", RemoteCommandJson.next());
        assertEquals("{\"action\":\"shuffle_on\"}", RemoteCommandJson.shuffle(true));
        assertEquals("{\"action\":\"shuffle_off\"}", RemoteCommandJson.shuffle(false));
        assertEquals("{\"action\":\"seek\",\"value\":37}", RemoteCommandJson.seek(37));
        assertEquals("{\"action\":\"set_rating\",\"value\":5}", RemoteCommandJson.rating(5));
    }

    @Test
    public void validatesNumericRanges() {
        assertInvalid(() -> RemoteCommandJson.seek(-1));
        assertInvalid(() -> RemoteCommandJson.rating(-1));
        assertInvalid(() -> RemoteCommandJson.rating(6));
    }

    @Test
    public void reconnectBackoffIsBounded() {
        assertEquals(1_000L, ReconnectBackoff.delayMilliseconds(0));
        assertEquals(2_000L, ReconnectBackoff.delayMilliseconds(1));
        assertEquals(8_000L, ReconnectBackoff.delayMilliseconds(3));
        assertEquals(15_000L, ReconnectBackoff.delayMilliseconds(4));
        assertEquals(15_000L, ReconnectBackoff.delayMilliseconds(100));
    }

    private static void assertInvalid(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected invalid argument");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
