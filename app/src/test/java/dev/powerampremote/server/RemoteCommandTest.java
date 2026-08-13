package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class RemoteCommandTest {
    @Test
    public void parsesEveryWireAction() {
        assertAction("play", RemoteCommand.Action.PLAY);
        assertAction("pause", RemoteCommand.Action.PAUSE);
        assertAction("previous", RemoteCommand.Action.PREVIOUS);
        assertAction("next", RemoteCommand.Action.NEXT);
        assertAction("shuffle_on", RemoteCommand.Action.SHUFFLE_ON);
        assertAction("shuffle_off", RemoteCommand.Action.SHUFFLE_OFF);

        RemoteCommand seek = RemoteCommand.parse("{\"action\":\"seek\",\"value\":37}");
        assertEquals(RemoteCommand.Action.SEEK, seek.action);
        assertEquals(37, seek.positionSeconds);

        RemoteCommand rating = RemoteCommand.parse("{\"action\":\"set_rating\",\"value\":3}");
        assertEquals(RemoteCommand.Action.SET_RATING, rating.action);
        assertEquals(3, rating.rating);

        RemoteCommand volume = RemoteCommand.parse("{\"action\":\"set_volume\",\"value\":11}");
        assertEquals(RemoteCommand.Action.SET_VOLUME, volume.action);
        assertEquals(11, volume.volume);
    }

    @Test
    public void acceptsSeekIntegerBoundariesWithoutConversion() {
        RemoteCommand atStart = RemoteCommand.parse("{\"action\":\"seek\",\"value\":0}");
        RemoteCommand maximum = RemoteCommand.parse(
                "{\"action\":\"seek\",\"value\":2147483647}"
        );

        assertEquals(0, atStart.positionSeconds);
        assertEquals(Integer.MAX_VALUE, maximum.positionSeconds);
        assertEquals(-1, atStart.rating);
    }

    @Test
    public void rejectsInvalidSeekRepresentations() {
        assertInvalid("{\"action\":\"seek\"}");
        assertInvalid("{\"action\":\"seek\",\"value\":null}");
        assertInvalid("{\"action\":\"seek\",\"value\":\"37\"}");
        assertInvalid("{\"action\":\"seek\",\"value\":37.0}");
        assertInvalid("{\"action\":\"seek\",\"value\":-1}");
        assertInvalid("{\"action\":\"seek\",\"value\":2147483648}");
    }

    @Test
    public void acceptsEveryExactRatingIncludingDislikeAndLike() {
        for (int rating = 0; rating <= 5; rating++) {
            RemoteCommand command = RemoteCommand.parse(
                    "{\"action\":\"set_rating\",\"value\":" + rating + "}"
            );
            assertEquals(RemoteCommand.Action.SET_RATING, command.action);
            assertEquals(rating, command.rating);
        }

        assertEquals(1, RemoteCommand.parse(
                "{\"action\":\"set_rating\",\"value\":1}"
        ).rating);
        assertEquals(5, RemoteCommand.parse(
                "{\"action\":\"set_rating\",\"value\":5}"
        ).rating);
    }

    @Test
    public void rejectsInvalidRatingRepresentations() {
        assertInvalid("{\"action\":\"set_rating\"}");
        assertInvalid("{\"action\":\"set_rating\",\"value\":null}");
        assertInvalid("{\"action\":\"set_rating\",\"value\":\"5\"}");
        assertInvalid("{\"action\":\"set_rating\",\"value\":1.0}");
        assertInvalid("{\"action\":\"set_rating\",\"value\":-1}");
        assertInvalid("{\"action\":\"set_rating\",\"value\":6}");
    }

    @Test
    public void validatesRemoteVolumeAsANonNegativeInteger() {
        assertEquals(0, RemoteCommand.parse(
                "{\"action\":\"set_volume\",\"value\":0}"
        ).volume);
        assertEquals(Integer.MAX_VALUE, RemoteCommand.parse(
                "{\"action\":\"set_volume\",\"value\":2147483647}"
        ).volume);
        assertInvalid("{\"action\":\"set_volume\"}");
        assertInvalid("{\"action\":\"set_volume\",\"value\":-1}");
        assertInvalid("{\"action\":\"set_volume\",\"value\":1.5}");
        assertInvalid("{\"action\":\"set_volume\",\"value\":2147483648}");
    }

    @Test
    public void rejectsUnknownDuplicateAndActionSpecificFields() {
        assertInvalid("{}");
        assertInvalid("{\"action\":\"unknown\"}");
        assertInvalid("{\"action\":\"play\",\"value\":1}");
        assertInvalid("{\"action\":\"play\",\"extra\":1}");
        assertInvalid("{\"action\":\"play\",\"action\":\"pause\"}");
        assertInvalid("{\"action\":\"play\"} trailing");
    }

    @Test
    public void rejectsMissingAndMalformedBodies() {
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.parse(null));
        assertInvalid("");
        assertInvalid("[]");
        assertInvalid("{\"action\":}");
        assertInvalid("{\"action\":\"play}");
    }

    private static void assertAction(String wireName, RemoteCommand.Action expected) {
        RemoteCommand command = RemoteCommand.parse("{\"action\":\"" + wireName + "\"}");
        assertEquals(expected, command.action);
        assertEquals(-1, command.rating);
        assertEquals(-1, command.positionSeconds);
        assertEquals(-1, command.volume);
    }

    private static void assertInvalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> RemoteCommand.parse(json));
    }
}
