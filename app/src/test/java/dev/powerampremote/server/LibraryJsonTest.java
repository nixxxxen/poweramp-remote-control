package dev.powerampremote.server;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class LibraryJsonTest {
    @Test
    public void serializesNeutralItemsNullsAndStructuredPlayTargets() throws Exception {
        LibraryItem item = new LibraryItem(
                LibraryItem.Type.PLAYLIST_ENTRY,
                41L,
                99L,
                7L,
                "A \"quoted\" title",
                null,
                "Album",
                123_456L,
                1_788_319_633L,
                17L,
                null,
                "/api/v1/library/artwork/tracks/41",
                LibraryItem.PlayTarget.playlistEntry(7L, 99L),
                null,
                null,
                null
        );
        PowerampLibrarySource.Page page = new PowerampLibrarySource.Page(
                "playlist_tracks",
                25,
                0,
                java.util.Collections.singletonList(item),
                null,
                false
        );
        JSONObject json = new JSONObject(LibraryJson.page(page));
        JSONObject serialized = json.getJSONArray("items").getJSONObject(0);
        assertEquals("playlist_entry", serialized.getString("type"));
        assertEquals(41L, serialized.getLong("id"));
        assertEquals(99L, serialized.getLong("entryId"));
        assertTrue(serialized.isNull("artist"));
        assertTrue(serialized.isNull("trackCount"));
        assertEquals("A \"quoted\" title", serialized.getString("title"));
        assertEquals(1_788_319_633L, serialized.getLong("dateAddedEpochSeconds"));
        assertEquals(17L, serialized.getLong("playCount"));
        assertEquals(7L, serialized.getJSONObject("play").getLong("playlistId"));
        assertEquals(99L, serialized.getJSONObject("play").getLong("entryId"));
        assertTrue(serialized.isNull("current"));
    }

    @Test
    public void capabilityJsonEnablesOnlyConfirmedQueueAppendWhenAvailable() throws Exception {
        JSONObject root = new JSONObject(LibraryJson.capabilities(new LibraryAccessState(
                LibraryAccessState.Status.PERMISSION_REQUIRED,
                2L
        )));
        assertEquals("permission_required", root.getString("status"));
        assertTrue(root.getBoolean("permissionRequired"));
        JSONObject queue = root.getJSONObject("queueCapabilities");
        assertTrue(queue.getBoolean("read"));
        assertTrue(queue.getBoolean("playExisting"));
        assertFalse(queue.getBoolean("add"));
        assertFalse(queue.getBoolean("remove"));
        assertFalse(queue.getBoolean("reorder"));
        assertFalse(queue.getBoolean("playNext"));
        assertEquals(
                "/api/v1/search/grouped",
                root.getJSONObject("routes").getString("categorizedSearch")
        );
        assertEquals(
                "/api/v1/library/artists/{id}/member-tracks",
                root.getJSONObject("routes").getString("artistMembershipTracks")
        );
        JSONObject pagination = root.getJSONObject("pagination");
        assertTrue(pagination.isNull("maximumContinuationRows"));
        assertEquals("server_snapshot", pagination.getString("continuationModel"));
        assertFalse(pagination.getBoolean("providerOffsetSupported"));
        assertEquals(1, root.getJSONObject("trackSorting")
                .getJSONArray("criteria").length());

        JSONObject available = new JSONObject(LibraryJson.capabilities(
                new LibraryAccessState(LibraryAccessState.Status.AVAILABLE, 3L)
        ));
        assertTrue(available.getJSONObject("queueCapabilities").getBoolean("add"));
        assertEquals("/api/v1/queue/add",
                available.getJSONObject("routes").getString("queueAdd"));
        JSONObject sorting = available.getJSONObject("trackSorting");
        assertEquals(7, sorting.getJSONArray("criteria").length());
        assertEquals("folder_files.created_at", sorting.getString("dateAddedField"));
        assertEquals("epoch_seconds", sorting.getString("dateAddedUnit"));
        assertEquals("folder_files.played_times", sorting.getString("playCountField"));
    }

    @Test
    public void serializesTypedSearchSectionsAndTrackMatchMode() throws Exception {
        LibraryItem track = new LibraryItem(
                LibraryItem.Type.TRACK, 41L, null, null, "Obsidian",
                "Northlane", "Obsidian", null, null,
                "/api/v1/library/artwork/tracks/41",
                LibraryItem.PlayTarget.track(41L), null
        );
        CategorizedSearch search = new CategorizedSearch(
                "Obsidian",
                25,
                CategorizedSearch.TrackMatch.EXACT,
                java.util.List.of(new CategorizedSearch.Section(
                        CategorizedSearch.SectionType.TRACKS,
                        java.util.Collections.singletonList(track),
                        true,
                        "ABCDEFGHIJKLMNOPQRSTUVWX"
                ))
        );

        JSONObject root = new JSONObject(LibraryJson.categorizedSearch(search));
        assertEquals("exact", root.getString("trackMatch"));
        JSONObject section = root.getJSONArray("sections").getJSONObject(0);
        assertEquals("tracks", section.getString("type"));
        assertEquals(41L, section.getJSONArray("items").getJSONObject(0).getLong("id"));
        assertTrue(section.getBoolean("truncated"));
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWX", section.getString("nextPageToken"));
    }

    @Test
    public void serializesAdditiveArtistMembershipBrowseTarget() throws Exception {
        LibraryItem artist = new LibraryItem(
                LibraryItem.Type.ARTIST, 8L, null, null, "Northlane",
                null, null, null, null, null, null, null,
                false, LibraryItem.BrowseTarget.artistMembership(8L)
        );
        CategorizedSearch search = new CategorizedSearch(
                "Northlane", 25, CategorizedSearch.TrackMatch.NONE,
                java.util.List.of(new CategorizedSearch.Section(
                        CategorizedSearch.SectionType.ARTISTS,
                        java.util.Collections.singletonList(artist), false
                ))
        );

        JSONObject serialized = new JSONObject(LibraryJson.categorizedSearch(search));
        JSONObject browse = serialized.getJSONArray("sections").getJSONObject(0)
                .getJSONArray("items").getJSONObject(0).getJSONObject("browse");
        assertEquals("artist_membership", browse.getString("type"));
        assertEquals(8L, browse.getLong("id"));
    }

    @Test
    public void parsesOnlyNarrowIdBasedPlayRequestsAndRejectsUris() {
        LibraryItem.PlayTarget queue = LibraryJson.parsePlayTarget(
                "{\"type\":\"queue_entry\",\"entryId\":91}"
        );
        assertEquals(LibraryItem.PlayTarget.Type.QUEUE_ENTRY, queue.type);
        assertEquals(91L, queue.id);

        LibraryItem.PlayTarget playlist = LibraryJson.parsePlayTarget(
                "{\"type\":\"playlist_entry\",\"playlistId\":7,\"entryId\":4}"
        );
        assertEquals(7L, playlist.containerId.longValue());
        assertEquals(4L, playlist.id);

        String[] invalid = {
                "{}",
                "{\"type\":\"track\",\"id\":0}",
                "{\"type\":\"track\",\"id\":1.0}",
                "{\"type\":\"track\",\"id\":1,\"uri\":\"file:///x\"}",
                "{\"type\":\"queue_entry\",\"entryId\":1,\"id\":2}",
                "{\"type\":\"unknown\",\"id\":1}",
                "{\"uri\":\"content://other.provider/x\"}"
        };
        for (String value : invalid) {
            try {
                LibraryJson.parsePlayTarget(value);
                fail("Expected rejection for " + value);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }
}
