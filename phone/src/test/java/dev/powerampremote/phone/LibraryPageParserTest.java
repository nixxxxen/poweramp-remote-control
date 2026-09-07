package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.json.JSONObject;

public final class LibraryPageParserTest {
    private static final String TOKEN = "abcdefghijklmnopqrstuvwx";

    @Test
    public void parsesTrackMetadataArtworkAndStructuredPlayTarget() throws Exception {
        LibraryPage page = LibraryPageParser.parse("{"
                + "\"category\":\"tracks\",\"limit\":25,\"offset\":0,"
                + "\"items\":[{\"type\":\"track\",\"id\":41,"
                + "\"entryId\":null,\"parentId\":null,\"title\":\"Song\","
                + "\"artist\":\"Artist\",\"album\":\"Album\","
                + "\"durationMilliseconds\":123000,\"trackCount\":null,"
                + "\"artwork\":\"/api/v1/library/artwork/tracks/41\","
                + "\"play\":{\"type\":\"track\",\"id\":41},\"current\":null}],"
                + "\"nextPageToken\":\"" + TOKEN + "\",\"truncated\":false}");

        assertEquals("tracks", page.category);
        assertEquals(1, page.items.size());
        LibraryItem item = page.items.get(0);
        assertEquals(41L, item.id);
        assertEquals("Song", item.title);
        assertEquals("Artist", item.artist);
        assertEquals(Long.valueOf(123000L), item.durationMilliseconds);
        assertNull(item.entryId);
        JSONObject play = new JSONObject(item.playTarget.toJson());
        assertEquals("track", play.getString("type"));
        assertEquals(41L, play.getLong("id"));
        assertEquals(TOKEN, page.nextPageToken);
        assertFalse(page.truncated);
    }

    @Test
    public void preservesNullableContainerFieldsWithoutRenderingSentinels() {
        LibraryPage page = LibraryPageParser.parse("{"
                + "\"category\":\"albums\",\"limit\":25,\"offset\":0,"
                + "\"items\":[{\"type\":\"album\",\"id\":8,"
                + "\"entryId\":null,\"parentId\":null,\"title\":\"Record\","
                + "\"artist\":null,\"album\":null,\"durationMilliseconds\":null,"
                + "\"trackCount\":7,\"artwork\":null,"
                + "\"play\":{\"type\":\"album\",\"id\":8},\"current\":null}],"
                + "\"nextPageToken\":null,\"truncated\":false}");

        LibraryItem item = page.items.get(0);
        assertNull(item.artist);
        assertNull(item.album);
        assertNull(item.artworkPath);
        assertEquals(Integer.valueOf(7), item.trackCount);
    }

    @Test
    public void rejectsUntrustedArtworkAndMalformedPageToken() {
        assertInvalid(pageWith("\"artwork\":\"http://example.test/a.jpg\"", "null"));
        assertInvalid(pageWith("\"artwork\":null", "\"short\""));
    }

    @Test
    public void paginatorFollowsOpaqueTokenAndStopsAtTruncation() {
        LibraryPager pager = new LibraryPager();
        LibraryPage first = LibraryPageParser.parse(pageWith(
                "\"artwork\":null", "\"" + TOKEN + "\""
        ));
        pager.accept(null, first);
        assertTrue(pager.canLoadMore());
        assertEquals(1, pager.items().size());

        LibraryPage last = LibraryPageParser.parse("{"
                + "\"category\":\"tracks\",\"limit\":25,\"offset\":25,"
                + "\"items\":[],\"nextPageToken\":null,\"truncated\":true}");
        pager.accept(TOKEN, last);
        assertFalse(pager.canLoadMore());
        assertTrue(pager.truncated());
        assertEquals(1, pager.items().size());
    }

    @Test
    public void paginatorRejectsWrongOrOutOfOrderContinuation() {
        LibraryPager pager = new LibraryPager();
        pager.accept(null, LibraryPageParser.parse(pageWith(
                "\"artwork\":null", "\"" + TOKEN + "\""
        )));
        try {
            pager.accept("zyxwvutsrqponmlkjihgfedc", LibraryPageParser.parse("{"
                    + "\"category\":\"tracks\",\"limit\":25,\"offset\":25,"
                    + "\"items\":[],\"nextPageToken\":null,\"truncated\":false}"));
            fail("Expected invalid continuation");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static String pageWith(String artwork, String token) {
        return "{\"category\":\"tracks\",\"limit\":25,\"offset\":0,"
                + "\"items\":[{\"type\":\"track\",\"id\":41,"
                + "\"entryId\":null,\"parentId\":null,\"title\":\"Song\","
                + "\"artist\":null,\"album\":null,\"durationMilliseconds\":null,"
                + "\"trackCount\":null," + artwork + ","
                + "\"play\":{\"type\":\"track\",\"id\":41},\"current\":null}],"
                + "\"nextPageToken\":" + token + ",\"truncated\":false}";
    }

    private static void assertInvalid(String json) {
        try {
            LibraryPageParser.parse(json);
            fail("Expected invalid page");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
