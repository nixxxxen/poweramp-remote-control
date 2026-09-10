package dev.powerampremote.phone;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SearchPresentationPolicyTest {
    @Test
    public void rendersTracksArtistsAlbumsInFixedOrderAndSkipsEmptySections() {
        LibraryItem track = item("track", 1L, "Track");
        LibraryItem artist = item("artist", 2L, "Artist");
        CategorizedSearchResult result = new CategorizedSearchResult(
                "query",
                25,
                "partial",
                List.of(
                        new CategorizedSearchResult.Section(
                                CategorizedSearchResult.SectionType.ARTISTS,
                                Collections.singletonList(artist),
                                false
                        ),
                        new CategorizedSearchResult.Section(
                                CategorizedSearchResult.SectionType.TRACKS,
                                Collections.singletonList(track),
                                true,
                                "ABCDEFGHIJKLMNOPQRSTUVWX"
                        )
                )
        );

        List<SearchPresentationPolicy.Row> rows = SearchPresentationPolicy.rows(result);
        assertEquals(5, rows.size());
        assertEquals(CategorizedSearchResult.SectionType.TRACKS, rows.get(0).header);
        assertEquals(track, rows.get(1).item);
        assertEquals(CategorizedSearchResult.SectionType.TRACKS, rows.get(2).more);
        assertEquals(CategorizedSearchResult.SectionType.ARTISTS, rows.get(3).header);
        assertEquals(artist, rows.get(4).item);
        assertTrue(rows.stream().noneMatch(row ->
                row.header == CategorizedSearchResult.SectionType.ALBUMS));
    }

    private static LibraryItem item(String type, long id, String title) {
        return new LibraryItem(
                type, id, null, null, title, null, null,
                null, null, null, null, null
        );
    }
}
