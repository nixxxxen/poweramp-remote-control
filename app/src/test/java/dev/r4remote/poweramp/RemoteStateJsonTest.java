package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteStateJsonTest {
    @Test
    public void preservesRawBitRateAndRawPositionInList() {
        TrackInfo track = new TrackInfo(
                12L,
                34L,
                "Track",
                "Album",
                "Artist",
                180,
                7,
                3,
                new TrackInfo.AudioProperties(
                        PowerampContract.FileTypes.FLAC,
                        "flac",
                        96_000,
                        24,
                        1_411_200
                ),
                new TrackInfo.PlaybackSource(
                        PowerampContract.Categories.QUEUE,
                        "content://com.maxmpz.audioplayer/queue",
                        0,
                        10
                )
        );
        RemotePlaybackState state = new RemotePlaybackState(
                8L,
                true,
                track,
                PowerampContract.STATE_PAUSED,
                7,
                1_000L,
                PowerampContract.ShuffleModes.SONGS,
                34L,
                true
        );

        String json = RemoteStateJson.toJson(state, 9_000L);

        assertContains(json, "\"bitRate\":1411200");
        assertFalse(json.contains("\"bitRate\":1411,"));
        assertContains(json, "\"positionInList\":0");
        assertFalse(json.contains("\"positionInList\":1,"));
        assertContains(json, "\"listSize\":10");
        assertContains(json, "\"artwork\":\"/api/v1/artwork\"");
    }

    @Test
    public void unavailableTrackFieldsAreJsonNull() {
        TrackInfo track = new TrackInfo(
                12L,
                34L,
                null,
                null,
                null,
                0,
                0,
                -1,
                TrackInfo.AudioProperties.UNKNOWN,
                TrackInfo.PlaybackSource.UNKNOWN
        );
        RemotePlaybackState state = new RemotePlaybackState(
                1L,
                true,
                track,
                PowerampContract.STATE_UNKNOWN,
                0,
                1_000L,
                -1,
                34L,
                false,
                false
        );

        String json = RemoteStateJson.toJson(state, 1_000L);

        assertContains(json, "\"title\":null");
        assertContains(json, "\"artist\":null");
        assertContains(json, "\"album\":null");
        assertContains(json, "\"artwork\":null");
        assertContains(json, "\"fileType\":null");
        assertContains(json, "\"fileTypeName\":null");
        assertContains(json, "\"codec\":null");
        assertContains(json, "\"bitsPerSample\":null");
        assertContains(json, "\"sampleRate\":null");
        assertContains(json, "\"bitRate\":null");
        assertContains(json, "\"sourceCategory\":null");
        assertContains(json, "\"sourceCategoryName\":null");
        assertContains(json, "\"sourceCategoryUri\":null");
        assertContains(json, "\"positionInList\":null");
        assertContains(json, "\"listSize\":null");
        assertContains(json, "\"durationSeconds\":null");
        assertContains(json, "\"positionSeconds\":null");
        assertContains(json, "\"playbackState\":null");
        assertContains(json, "\"rating\":null");
        assertContains(json, "\"liked\":null");
        assertContains(json, "\"disliked\":null");
        assertContains(json, "\"shuffle\":null");
        assertContains(json, "\"shuffleMode\":null");
        assertFalse(json.contains("\"null\""));
    }

    @Test
    public void noTrackUsesNullForTrackDependentValues() {
        String json = RemoteStateJson.toJson(RemotePlaybackState.initial(500L), 500L);

        assertContains(json, "\"powerampAvailable\":false");
        assertContains(json, "\"hasTrack\":false");
        assertContains(json, "\"durationSeconds\":null");
        assertContains(json, "\"positionSeconds\":null");
        assertContains(json, "\"rating\":null");
    }

    @Test
    public void exposesLikeAndDislikeFromExactRatingValues() {
        String disliked = jsonForRating(1);
        assertContains(disliked, "\"rating\":1");
        assertContains(disliked, "\"liked\":false");
        assertContains(disliked, "\"disliked\":true");

        String liked = jsonForRating(5);
        assertContains(liked, "\"rating\":5");
        assertContains(liked, "\"liked\":true");
        assertContains(liked, "\"disliked\":false");

        String neutral = jsonForRating(3);
        assertContains(neutral, "\"liked\":false");
        assertContains(neutral, "\"disliked\":false");
    }

    @Test
    public void escapesMetadataAsValidJsonStrings() {
        TrackInfo track = new TrackInfo(
                1L,
                2L,
                "Quote \" and slash \\",
                "Line\nBreak",
                "Tab\tArtist",
                1,
                0
        );
        RemotePlaybackState state = new RemotePlaybackState(
                1L,
                true,
                track,
                PowerampContract.STATE_STOPPED,
                0,
                0L,
                PowerampContract.ShuffleModes.NONE,
                2L,
                false
        );

        String json = RemoteStateJson.toJson(state, 0L);

        assertContains(json, "\"title\":\"Quote \\\" and slash \\\\\"");
        assertContains(json, "\"album\":\"Line\\nBreak\"");
        assertContains(json, "\"artist\":\"Tab\\tArtist\"");
    }

    private static String jsonForRating(int rating) {
        TrackInfo track = new TrackInfo(
                1L,
                2L,
                "Track",
                "Album",
                "Artist",
                100,
                0,
                rating,
                TrackInfo.AudioProperties.UNKNOWN,
                TrackInfo.PlaybackSource.UNKNOWN
        );
        RemotePlaybackState state = new RemotePlaybackState(
                1L,
                true,
                track,
                PowerampContract.STATE_PAUSED,
                0,
                0L,
                PowerampContract.ShuffleModes.NONE,
                2L,
                false
        );
        return RemoteStateJson.toJson(state, 0L);
    }

    private static void assertContains(String json, String expected) {
        assertTrue("Expected JSON to contain " + expected + ", but was: " + json,
                json.contains(expected));
    }
}
