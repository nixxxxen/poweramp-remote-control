package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class CategorizedSearchParserTest {
    @Test
    public void parsesTypedTrackArtistAndAlbumRowsWithStableIds() {
        CategorizedSearchResult result = CategorizedSearchParser.parse("{"
                + "\"query\":\"Obsidian\",\"limit\":25,\"trackMatch\":\"exact\","
                + "\"sections\":["
                + section("tracks", item("track", 41L, "Obsidian", true)) + ","
                + section("artists", item("artist", 8L, "Northlane", false)) + ","
                + section("albums", item("album", 9L, "Obsidian", false))
                + "]}");

        assertEquals("exact", result.trackMatch);
        assertEquals(3, result.sections.size());
        LibraryItem track = result.sections.get(0).items.get(0);
        assertEquals(Long.valueOf(41L), track.underlyingId);
        assertEquals(8L, result.sections.get(1).items.get(0).id);
        assertNull(result.sections.get(1).items.get(0).underlyingId);
        assertFalse(result.truncated());
    }

    @Test
    public void rejectsOutOfOrderSectionsAndWrongEntityTypes() {
        assertInvalid("{\"query\":\"x\",\"limit\":25,\"trackMatch\":\"none\","
                + "\"sections\":["
                + section("albums", item("album", 9L, "x", false)) + ","
                + section("artists", item("artist", 8L, "x", false)) + "]}");
        assertInvalid("{\"query\":\"x\",\"limit\":25,\"trackMatch\":\"none\","
                + "\"sections\":["
                + section("artists", item("album", 9L, "x", false)) + "]}");
    }

    private static String section(String type, String item) {
        return "{\"type\":\"" + type + "\",\"items\":[" + item
                + "],\"truncated\":false}";
    }

    private static String item(String type, long id, String title, boolean playable) {
        return "{\"type\":\"" + type + "\",\"id\":" + id
                + ",\"entryId\":null,\"parentId\":null,\"title\":\"" + title
                + "\",\"artist\":null,\"album\":null,"
                + "\"durationMilliseconds\":null,\"trackCount\":null,"
                + "\"artwork\":" + (playable
                        ? "\"/api/v1/library/artwork/tracks/" + id + "\""
                        : "null")
                + ",\"play\":" + (playable
                        ? "{\"type\":\"track\",\"id\":" + id + "}"
                        : "null")
                + ",\"current\":null}";
    }

    private static void assertInvalid(String value) {
        try {
            CategorizedSearchParser.parse(value);
            fail("Expected invalid categorized Search response");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
