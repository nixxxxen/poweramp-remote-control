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
                "/api/v1/queue?limit=25",
                LibraryRequest.queue().path(null)
        );
        assertFalse(LibraryRequest.queue().isTrackList());
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

    @Test
    public void trackSortIsAdditiveAndBoundToEveryContinuationRequest() {
        LibraryRequest original = LibraryRequest.playlistTracks(8L);
        assertEquals(
                "/api/v1/library/playlists/8/tracks?limit=25",
                original.path(null)
        );
        assertEquals(LibrarySortView.PLAYLIST, original.sortView);

        LibraryRequest sorted = original.withSort(new LibrarySort(
                LibrarySort.Criterion.PLAY_COUNT,
                LibrarySort.Direction.DESCENDING
        ));
        assertEquals(
                "/api/v1/library/playlists/8/tracks?limit=25"
                        + "&sort=play_count&direction=desc"
                        + "&pageToken=abcdefghijklmnopqrstuvwx",
                sorted.path("abcdefghijklmnopqrstuvwx")
        );
        assertEquals(LibrarySortView.ALL_TRACKS, LibraryRequest.tracks().sortView);
        assertEquals(LibrarySortView.ARTIST, LibraryRequest.artistTracks(1L).sortView);
        assertEquals(LibrarySortView.ALBUM, LibraryRequest.albumTracks(1L).sortView);
        assertEquals(LibrarySortView.FOLDER, LibraryRequest.folderTracks(1L).sortView);
        assertFalse(LibraryRequest.search("query").isTrackList());
    }
}
