package dev.powerampremote.phone;

import org.junit.Test;

import java.util.List;

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
    public void parsesAdditiveArtistMembershipBrowseAndAcceptsOldPayloadWithoutIt() {
        String membershipArtist = item("artist", 8L, "Northlane", false)
                .replace("\"current\":null", "\"browse\":{\"type\":"
                        + "\"artist_membership\",\"id\":8},\"current\":null");
        CategorizedSearchResult current = CategorizedSearchParser.parse("{"
                + "\"query\":\"Northlane\",\"limit\":25,\"trackMatch\":\"none\","
                + "\"sections\":[" + section("artists", membershipArtist) + "]}");
        LibraryItem artist = current.sections.get(0).items.get(0);
        assertEquals(LibraryBrowseTarget.TYPE_ARTIST_MEMBERSHIP, artist.browseTarget.type);
        assertEquals(RepresentativeArtworkKey.TYPE_ARTIST_MEMBERSHIP,
                artist.representativeType());

        CategorizedSearchResult legacy = CategorizedSearchParser.parse("{"
                + "\"query\":\"Northlane\",\"limit\":25,\"trackMatch\":\"none\","
                + "\"sections\":[" + section(
                        "artists", item("artist", 8L, "Northlane", false)
                ) + "]}");
        assertNull(legacy.sections.get(0).items.get(0).browseTarget);
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

    @Test
    public void parsesAdditiveSectionTokenAndMergesOnlyThatSection() {
        String token = "ABCDEFGHIJKLMNOPQRSTUVWX";
        CategorizedSearchResult initial = CategorizedSearchParser.parse("{"
                + "\"query\":\"x\",\"limit\":25,\"trackMatch\":\"partial\","
                + "\"sections\":["
                + sectionWithToken("tracks", item("track", 1L, "x track", true), token)
                + "," + sectionWithToken(
                        "artists", item("artist", 2L, "x artist", false), token
                ) + "]}");
        CategorizedSearchResult artistPage = new CategorizedSearchResult(
                "x",
                25,
                "partial",
                List.of(new CategorizedSearchResult.Section(
                        CategorizedSearchResult.SectionType.ARTISTS,
                        List.of(CategorizedSearchParser.parse("{"
                                + "\"query\":\"x\",\"limit\":25,"
                                + "\"trackMatch\":\"none\",\"sections\":["
                                + section("artists", item(
                                        "artist", 3L, "x artist two", false
                                )) + "]}").sections.get(0).items.get(0)),
                        false,
                        null
                ))
        );

        CategorizedSearchResult merged = initial.mergeSection(
                CategorizedSearchResult.SectionType.ARTISTS, artistPage, false
        );
        assertEquals(token, merged.section(
                CategorizedSearchResult.SectionType.TRACKS
        ).nextPageToken);
        assertEquals(2, merged.section(
                CategorizedSearchResult.SectionType.ARTISTS
        ).items.size());
        assertNull(merged.section(
                CategorizedSearchResult.SectionType.ARTISTS
        ).nextPageToken);
    }

    private static String section(String type, String item) {
        return "{\"type\":\"" + type + "\",\"items\":[" + item
                + "],\"truncated\":false}";
    }

    private static String sectionWithToken(String type, String item, String token) {
        return "{\"type\":\"" + type + "\",\"items\":[" + item
                + "],\"truncated\":true,\"nextPageToken\":\"" + token + "\"}";
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
