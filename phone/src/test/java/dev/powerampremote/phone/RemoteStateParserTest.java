package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class RemoteStateParserTest {
    private static final String COMPLETE_STATE = "{"
            + "\"apiVersion\":1,\"revision\":42,"
            + "\"powerampAvailable\":true,\"hasTrack\":true,"
            + "\"trackId\":73,\"trackRealId\":41,"
            + "\"title\":\"Track\",\"artist\":\"Artist\",\"album\":\"Album\","
            + "\"artwork\":\"/api/v1/artwork\","
            + "\"fileType\":1,\"fileTypeName\":\"FLAC\",\"codec\":\"flac\","
            + "\"bitsPerSample\":24,\"sampleRate\":96000,\"bitRate\":1411200,"
            + "\"sourceCategory\":800,\"sourceCategoryName\":\"Queue\","
            + "\"sourceCategoryUri\":\"content://queue\","
            + "\"positionInList\":0,\"listSize\":10,"
            + "\"durationSeconds\":180,\"positionSeconds\":37,"
            + "\"playbackState\":\"playing\",\"rating\":5,"
            + "\"liked\":true,\"disliked\":false,\"shuffle\":true,\"shuffleMode\":2,"
            + "\"volume\":7,\"volumeMax\":15,\"volumeControlAvailable\":true}";

    @Test
    public void parsesCompleteServerSnapshotWithoutChangingRawValues() {
        RemoteState state = RemoteStateParser.parse(COMPLETE_STATE);
        assertEquals(42L, state.revision);
        assertTrue(state.powerampAvailable);
        assertTrue(state.hasTrack);
        assertEquals(Long.valueOf(73L), state.trackId);
        assertEquals(Long.valueOf(41L), state.trackRealId);
        assertEquals("Track", state.title);
        assertEquals(Integer.valueOf(1411200), state.bitRate);
        assertEquals(Integer.valueOf(0), state.positionInList);
        assertEquals("playing", state.playbackState);
        assertEquals(Integer.valueOf(5), state.rating);
        assertEquals(Boolean.TRUE, state.shuffle);
        assertEquals(Integer.valueOf(7), state.volume);
        assertEquals(Integer.valueOf(15), state.volumeMax);
        assertEquals(Boolean.TRUE, state.volumeControlAvailable);
    }

    @Test
    public void preservesNullOptionalFields() {
        String json = COMPLETE_STATE.replace("\"/api/v1/artwork\"", "null")
                .replace("1411200", "null").replace("\"playing\"", "null")
                .replace("\"rating\":5", "\"rating\":null")
                .replace("\"liked\":true", "\"liked\":null");
        RemoteState state = RemoteStateParser.parse(json);
        assertNull(state.artwork);
        assertNull(state.bitRate);
        assertNull(state.playbackState);
        assertNull(state.rating);
        assertNull(state.liked);
        assertFalse(Boolean.TRUE.equals(state.disliked));
    }

    @Test
    public void decodesEscapedStrings() {
        RemoteState state = RemoteStateParser.parse(COMPLETE_STATE.replace(
                "\"title\":\"Track\"", "\"title\":\"Line\\n\\u0422\""
        ));
        assertEquals("Line\nТ", state.title);
    }

    @Test
    public void rejectsWrongApiVersionAndUntrustedArtworkPath() {
        assertInvalid(COMPLETE_STATE.replace("\"apiVersion\":1", "\"apiVersion\":2"));
        assertInvalid(COMPLETE_STATE.replace("/api/v1/artwork", "http://example.test/x"));
    }

    @Test
    public void rejectsDuplicateKeysAndFractionalNumbers() {
        assertInvalid(COMPLETE_STATE.replace("\"revision\":42", "\"revision\":42,\"revision\":43"));
        assertInvalid(COMPLETE_STATE.replace("\"revision\":42", "\"revision\":4.2"));
    }

    @Test
    public void remainsCompatibleWithOlderApiV1SnapshotsWithoutVolumeFields() {
        String legacy = COMPLETE_STATE
                .replace(",\"volume\":7", "")
                .replace(",\"volumeMax\":15", "")
                .replace(",\"volumeControlAvailable\":true", "");

        RemoteState state = RemoteStateParser.parse(legacy);

        assertNull(state.volume);
        assertNull(state.volumeMax);
        assertNull(state.volumeControlAvailable);
    }

    @Test
    public void remainsCompatibleWithOlderApiV1SnapshotsWithoutTrackIdentityFields() {
        String legacy = COMPLETE_STATE
                .replace("\"trackId\":73,\"trackRealId\":41,", "");

        RemoteState state = RemoteStateParser.parse(legacy);

        assertNull(state.trackId);
        assertNull(state.trackRealId);
    }

    @Test
    public void parsesNullableTrackIdentityFields() {
        RemoteState state = RemoteStateParser.parse(COMPLETE_STATE
                .replace("\"trackId\":73", "\"trackId\":null")
                .replace("\"trackRealId\":41", "\"trackRealId\":null"));

        assertNull(state.trackId);
        assertNull(state.trackRealId);
    }

    @Test
    public void artworkKeyIgnoresRevisionAndPlaybackPositionChanges() {
        RemoteState first = RemoteStateParser.parse(COMPLETE_STATE);
        RemoteState laterSnapshot = RemoteStateParser.parse(COMPLETE_STATE
                .replace("\"revision\":42", "\"revision\":43")
                .replace("\"positionSeconds\":37", "\"positionSeconds\":91"));
        RemoteState metadataCorrection = RemoteStateParser.parse(COMPLETE_STATE.replace(
                "\"title\":\"Track\"", "\"title\":\"Corrected title\""
        ));
        RemoteState nextTrack = RemoteStateParser.parse(COMPLETE_STATE
                .replace("\"trackId\":73", "\"trackId\":74")
                .replace("\"trackRealId\":41", "\"trackRealId\":42"));

        assertEquals(first.artworkKey(), laterSnapshot.artworkKey());
        assertEquals(first.artworkKey(), metadataCorrection.artworkKey());
        assertFalse(first.artworkKey().equals(nextTrack.artworkKey()));
    }

    private static void assertInvalid(String json) {
        try {
            RemoteStateParser.parse(json);
            fail("Expected invalid state");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
