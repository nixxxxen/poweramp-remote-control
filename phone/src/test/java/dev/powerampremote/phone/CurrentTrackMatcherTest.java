package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class CurrentTrackMatcherTest {
    @Test
    public void parsedStateMatchesParsedTrackRowsButNeverLibraryContainers() {
        RemoteState state = state(41L, 41L, RemoteSourceCategory.FILES, "Remote metadata");
        LibraryPage page = LibraryPageParser.parse("{"
                + "\"category\":\"tracks\",\"limit\":5,\"offset\":0,\"items\":["
                + itemJson("track", 41L, "Track") + ","
                + itemJson("artist", 41L, "Artist") + ","
                + itemJson("album", 41L, "Album") + ","
                + itemJson("folder", 41L, "Folder") + ","
                + itemJson("playlist", 41L, "Playlist")
                + "],\"nextPageToken\":null,\"truncated\":false}");

        assertEquals("track", page.items.get(0).type);
        assertEquals(41L, page.items.get(0).id);
        assertEquals(Long.valueOf(41L), page.items.get(0).underlyingId);
        assertTrue(CurrentTrackMatcher.matches(state, page.items.get(0)));
        for (int index = 1; index < page.items.size(); index++) {
            assertNull(page.items.get(index).underlyingId);
            assertFalse(CurrentTrackMatcher.matches(state, page.items.get(index)));
        }
    }

    @Test
    public void regularTrackMatchesOnlyUnderlyingRealId() {
        LibraryItem current = track("track", 41L, null, "Different metadata");
        LibraryItem wrong = track("track", 42L, null, "Track");
        RemoteState state = state(73L, 41L, RemoteSourceCategory.PLAYLISTS, "Track");

        assertTrue(CurrentTrackMatcher.matches(state, current));
        assertFalse(CurrentTrackMatcher.matches(state, wrong));
    }

    @Test
    public void missingIdAndMetadataOnlyMatchNeverMarkTrack() {
        LibraryItem metadataMatch = track("track", 41L, null, "Track");
        LibraryItem staleQueueFlag = new LibraryItem(
                "queue_entry", 41L, 502L, null, "Track", "Artist", "Album",
                180_000L, null, null, null, true
        );

        assertFalse(CurrentTrackMatcher.matches(
                state(null, null, RemoteSourceCategory.FILES, "Track"), metadataMatch
        ));
        assertFalse(CurrentTrackMatcher.matches(
                state(41L, null, RemoteSourceCategory.FILES, "Track"), metadataMatch
        ));
        assertFalse(CurrentTrackMatcher.matches(
                state(73L, 99L, RemoteSourceCategory.FILES, "Track"), metadataMatch
        ));
        assertTrue(staleQueueFlag.current);
        assertFalse(CurrentTrackMatcher.matches(
                state(501L, 41L, RemoteSourceCategory.QUEUE, "Track"), staleQueueFlag
        ));
    }

    @Test
    public void duplicateQueueTracksUseExactEntryAndUnderlyingIds() {
        RemoteState state = state(502L, 41L, RemoteSourceCategory.QUEUE, "Track");
        LibraryItem firstDuplicate = track("queue_entry", 41L, 501L, "Track");
        LibraryItem exactEntry = track("queue_entry", 41L, 502L, "Track");

        assertFalse(CurrentTrackMatcher.matches(state, firstDuplicate));
        assertTrue(CurrentTrackMatcher.matches(state, exactEntry));
        assertFalse(CurrentTrackMatcher.matches(
                state(502L, 41L, RemoteSourceCategory.PLAYLISTS, "Track"), exactEntry
        ));
        assertFalse(CurrentTrackMatcher.matches(
                state(502L, 42L, RemoteSourceCategory.QUEUE, "Track"), exactEntry
        ));
    }

    @Test
    public void playlistDuplicatesStayUnmarkedWithoutContainerIdentity() {
        RemoteState state = state(702L, 41L, RemoteSourceCategory.PLAYLISTS, "Track");

        assertFalse(CurrentTrackMatcher.matches(
                state, track("playlist_entry", 41L, 702L, "Track")
        ));
        assertFalse(CurrentTrackMatcher.matches(
                state, track("playlist_entry", 41L, 703L, "Track")
        ));
    }

    @Test
    public void repeatedConfirmedStateRenderDoesNotReplaceRows() {
        List<LibraryItem> rows = Arrays.asList(
                track("track", 41L, null, "First"),
                track("track", 42L, null, "Second")
        );
        LibraryItem firstRow = rows.get(0);
        LibraryItem secondRow = rows.get(1);
        CurrentTrackMatcher.IndicatorState indicator =
                new CurrentTrackMatcher.IndicatorState();

        indicator.update(state(41L, 41L, RemoteSourceCategory.FILES, "First"));
        assertTrue(indicator.matches(rows.get(0)));
        assertFalse(indicator.matches(rows.get(1)));
        indicator.update(state(42L, 42L, RemoteSourceCategory.FILES, "Second"));
        assertFalse(indicator.matches(rows.get(0)));
        assertTrue(indicator.matches(rows.get(1)));
        assertSame(firstRow, rows.get(0));
        assertSame(secondRow, rows.get(1));
    }

    private static LibraryItem track(String type, long id, Long entryId, String title) {
        return new LibraryItem(
                type, id, entryId, null, title, "Artist", "Album",
                180_000L, null, null, null, null
        );
    }

    private static String itemJson(String type, long id, String title) {
        return "{\"type\":\"" + type + "\",\"id\":" + id
                + ",\"entryId\":null,\"parentId\":null,\"title\":\"" + title
                + "\",\"artist\":null,\"album\":null,"
                + "\"durationMilliseconds\":null,\"trackCount\":null,"
                + "\"artwork\":null,\"play\":null,\"current\":null}";
    }

    private static RemoteState state(
            Long trackId,
            Long trackRealId,
            int sourceCategory,
            String title
    ) {
        StringBuilder json = new StringBuilder("{")
                .append("\"apiVersion\":1,\"revision\":1,")
                .append("\"powerampAvailable\":true,\"hasTrack\":true,")
                .append("\"trackId\":").append(trackId == null ? "null" : trackId)
                .append(",\"trackRealId\":")
                .append(trackRealId == null ? "null" : trackRealId)
                .append(",\"sourceCategory\":").append(sourceCategory)
                .append(",\"title\":\"").append(title).append("\",")
                .append("\"playbackState\":\"paused\"}");
        return RemoteStateParser.parse(json.toString());
    }
}
