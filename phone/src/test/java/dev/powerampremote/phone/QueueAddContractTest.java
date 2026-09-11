package dev.powerampremote.phone;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class QueueAddContractTest {
    @Test
    public void requestPreservesOrderAndDuplicateOccurrenceIdentities() throws Exception {
        LibraryPlayTarget track = target("{\"type\":\"track\",\"id\":41}");
        LibraryPlayTarget playlist = target(
                "{\"type\":\"playlist_entry\",\"playlistId\":7,\"entryId\":99}"
        );
        LibraryPlayTarget queue = target(
                "{\"type\":\"queue_entry\",\"entryId\":123}"
        );
        JSONObject json = new JSONObject(new QueueAddRequest(
                Arrays.asList(track, playlist, queue, track)
        ).toJson());

        assertEquals(4, json.getJSONArray("items").length());
        assertEquals(41L, json.getJSONArray("items").getJSONObject(0).getLong("id"));
        assertEquals(99L, json.getJSONArray("items").getJSONObject(1).getLong("entryId"));
        assertEquals(123L, json.getJSONArray("items").getJSONObject(2).getLong("entryId"));
        assertEquals(41L, json.getJSONArray("items").getJSONObject(3).getLong("id"));
    }

    @Test
    public void selectionDistinguishesPlaylistAndQueueDuplicatesAndKeepsOrder()
            throws Exception {
        LibraryPlayTarget playlistOne = target(
                "{\"type\":\"playlist_entry\",\"playlistId\":7,\"entryId\":99}"
        );
        LibraryPlayTarget playlistTwo = target(
                "{\"type\":\"playlist_entry\",\"playlistId\":7,\"entryId\":100}"
        );
        LibraryPlayTarget queueOne = target(
                "{\"type\":\"queue_entry\",\"entryId\":20}"
        );
        LibraryPlayTarget queueTwo = target(
                "{\"type\":\"queue_entry\",\"entryId\":21}"
        );
        TrackSelection selection = new TrackSelection();
        assertTrue(selection.toggle(playlistOne));
        assertTrue(selection.toggle(queueOne));
        assertTrue(selection.toggle(playlistTwo));
        assertTrue(selection.toggle(queueTwo));
        assertEquals(4, selection.size());
        assertEquals(20L, selection.targets().get(1).entryId.longValue());
        selection.removeFirst(2);
        assertEquals(2, selection.size());
        assertTrue(selection.contains(playlistTwo));
        assertTrue(selection.contains(queueTwo));
        assertFalse(selection.contains(playlistOne));
    }

    @Test
    public void partialResultDoesNotClaimAtomicCompletion() {
        QueueAddResult result = QueueAddResult.parse(
                "{\"requestedCount\":4,\"addedCount\":2,\"complete\":false,"
                        + "\"failedIndex\":2,\"failure\":\"insert_failed\","
                        + "\"status\":\"available\"}"
        );
        assertEquals(4, result.requestedCount);
        assertEquals(2, result.addedCount);
        assertEquals(Integer.valueOf(2), result.failedIndex);
        assertFalse(result.complete);
    }

    private static LibraryPlayTarget target(String json) throws Exception {
        return LibraryPlayTarget.parse(new JSONObject(json));
    }
}
