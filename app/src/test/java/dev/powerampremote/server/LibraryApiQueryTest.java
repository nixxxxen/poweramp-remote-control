package dev.powerampremote.server;

import org.junit.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class LibraryApiQueryTest {
    private static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWX";

    @Test
    public void parsesBoundedPageAndUtf8SearchParameters() throws Exception {
        LibraryApiQuery defaults = LibraryApiQuery.page(null);
        assertEquals(PowerampLibraryContract.DEFAULT_PAGE_SIZE, defaults.limit);
        assertNull(defaults.pageToken);

        LibraryApiQuery page = LibraryApiQuery.page("limit=100&pageToken=" + TOKEN);
        assertEquals(100, page.limit);
        assertEquals(TOKEN, page.pageToken);

        String query = URLEncoder.encode("Björk live", StandardCharsets.UTF_8.name());
        LibraryApiQuery search = LibraryApiQuery.search("q=" + query + "&limit=3");
        assertEquals("Björk live", search.searchQuery);
        assertEquals(3, search.limit);

        LibraryApiQuery categorized = LibraryApiQuery.categorizedSearch(
                "q=" + query + "&limit=3&section=artists&pageToken=" + TOKEN
        );
        assertEquals("artists", categorized.searchSection);
        assertEquals(TOKEN, categorized.pageToken);
    }

    @Test
    public void rejectsInvalidLimitTokenQueryAndDuplicateParameters() {
        String[] rejectedPages = {
                "",
                "limit=0",
                "limit=101",
                "limit=01",
                "limit=x",
                "pageToken=short",
                "limit=2&limit=3",
                "offset=1",
                "q=private"
        };
        for (String query : rejectedPages) {
            expectInvalid(() -> LibraryApiQuery.page(query));
        }

        String[] rejectedSearches = {
                null,
                "q=",
                "q=%GG",
                "q=%C3%28",
                "q=one&q=two",
                "query=music",
                "q=line%0Abreak"
        };
        for (String query : rejectedSearches) {
            expectInvalid(() -> LibraryApiQuery.search(query));
        }
        expectInvalid(() -> LibraryApiQuery.categorizedSearch("q=x&section=queue"));
        expectInvalid(() -> LibraryApiQuery.search("q=x&section=tracks"));
    }

    @Test
    public void routesWithoutParametersRejectEvenEmptyQueryMarker() {
        LibraryApiQuery.requireEmpty(null);
        expectInvalid(() -> LibraryApiQuery.requireEmpty(""));
        expectInvalid(() -> LibraryApiQuery.requireEmpty("x=1"));
    }

    private static void expectInvalid(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
