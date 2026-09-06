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
                null,
                "/api/v1/library/artwork/tracks/41",
                LibraryItem.PlayTarget.playlistEntry(7L, 99L),
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
        assertEquals(7L, serialized.getJSONObject("play").getLong("playlistId"));
        assertEquals(99L, serialized.getJSONObject("play").getLong("entryId"));
        assertTrue(serialized.isNull("current"));
    }

    @Test
    public void capabilityJsonKeepsEveryQueueMutationDisabled() throws Exception {
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
