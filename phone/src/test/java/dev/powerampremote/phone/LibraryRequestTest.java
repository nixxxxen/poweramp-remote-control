package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class LibraryRequestTest {
    @Test
    public void searchEncodesUserTextAndContinuationAsQueryParameters() {
        LibraryRequest request = LibraryRequest.search("A&B + трек");
        String first = request.path(null);
        assertEquals(
                "/api/v1/search?q=A%26B+%2B+%D1%82%D1%80%D0%B5%D0%BA&limit=25",
                first
        );
        assertFalse(first.contains("A&B"));
        assertEquals(
                first + "&pageToken=abcdefghijklmnopqrstuvwx",
                request.path("abcdefghijklmnopqrstuvwx")
        );
    }

    @Test
    public void containerRequestsUseOnlyFixedApiV1Routes() {
        assertEquals(
                "/api/v1/library/albums/8/tracks?limit=25",
                LibraryRequest.albumTracks(8L).path(null)
        );
        assertEquals(
                "/api/v1/library/artists/8/member-tracks?limit=25",
                LibraryRequest.artistMemberTracks(8L).path(null)
        );
        assertEquals(
                "/api/v1/library/folder-tree/0/folders?limit=25",
                LibraryRequest.subfolders(0L).path(null)
        );
        assertEquals(
                "/api/v1/search/grouped?q=A%26B+%2B+%D1%82%D1%80%D0%B5%D0%BA&limit=25",
                CategorizedSearchRequest.create("A&B + трек").path()
        );
        assertEquals(
                "/api/v1/search/grouped?q=Oblivion&limit=25&section=tracks"
                        + "&pageToken=ABCDEFGHIJKLMNOPQRSTUVWX",
                CategorizedSearchRequest.section(
                        "Oblivion",
                        CategorizedSearchResult.SectionType.TRACKS,
                        "ABCDEFGHIJKLMNOPQRSTUVWX"
                ).path()
        );
    }
}
