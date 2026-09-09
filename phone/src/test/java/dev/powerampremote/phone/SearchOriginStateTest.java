package dev.powerampremote.phone;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class SearchOriginStateTest {
    @Test
    public void artistAndAlbumContainerNavigationRetainsExactSearchPresentation() {
        CategorizedSearchResult result = new CategorizedSearchResult(
                "Obsidian", 25, "exact", Collections.emptyList()
        );
        SearchOriginState state = new SearchOriginState();

        assertTrue(SearchOriginState.supportsDestination(item("artist", 8L)));
        assertTrue(SearchOriginState.supportsDestination(item("album", 9L)));
        assertFalse(SearchOriginState.supportsDestination(item("track", 41L)));
        assertTrue(state.begin("Obsidian", result, 7, -12, 3));
        assertFalse(state.begin("replacement", result, 0, 0, 1));

        SearchOriginState.Snapshot snapshot = state.consume();
        assertEquals("Obsidian", snapshot.query);
        assertSame(result, snapshot.result);
        assertEquals(7, snapshot.firstVisible);
        assertEquals(-12, snapshot.topOffset);
        assertEquals(3, snapshot.libraryDepth);
        assertFalse(state.active());
    }

    private static LibraryItem item(String type, long id) {
        return new LibraryItem(
                type, id, null, null, type, null, null,
                null, null, null, null, null
        );
    }
}
