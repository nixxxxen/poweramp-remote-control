package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class QueueAddRequestTest {
    @Test
    public void acceptsOnlyStructuredTrackOccurrenceTargets() {
        QueueAddRequest request = QueueAddRequest.parse("{\"items\":["
                + "{\"type\":\"track\",\"id\":41},"
                + "{\"type\":\"playlist_entry\",\"playlistId\":7,\"entryId\":99},"
                + "{\"type\":\"queue_entry\",\"entryId\":123}]}");
        assertEquals(3, request.items.size());
        assertEquals(LibraryItem.PlayTarget.Type.TRACK, request.items.get(0).type);
        assertEquals(LibraryItem.PlayTarget.Type.PLAYLIST_ENTRY, request.items.get(1).type);
        assertEquals(LibraryItem.PlayTarget.Type.QUEUE_ENTRY, request.items.get(2).type);
    }

    @Test
    public void rejectsEmptyOversizedNonIntegerAndUnknownFields() {
        invalid("{\"items\":[]}");
        invalid("{\"items\":[{\"type\":\"track\",\"id\":0}]}");
        invalid("{\"items\":[{\"type\":\"track\",\"id\":1.5}]}");
        invalid("{\"items\":[{\"type\":\"track\",\"id\":1,\"sort\":9}]}");
        invalid("{\"items\":[{\"type\":\"track\",\"id\":1}],\"uri\":\"x\"}");
        invalid("{\"items\":[{\"type\":\"album\",\"id\":1}]}");

        StringBuilder oversized = new StringBuilder("{\"items\":[");
        for (int index = 0; index <= QueueAddRequest.MAX_ITEMS; index++) {
            if (index > 0) oversized.append(',');
            oversized.append("{\"type\":\"track\",\"id\":1}");
        }
        oversized.append("]}");
        invalid(oversized.toString());
    }

    private static void invalid(String json) {
        try {
            QueueAddRequest.parse(json);
            fail("Expected invalid Queue add request");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
