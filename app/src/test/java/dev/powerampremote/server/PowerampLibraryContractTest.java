package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PowerampLibraryContractTest {
    @Test
    public void buildsOnlyDocumentedProviderAndOpenToPlayUris() {
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=25",
                PowerampLibraryContract.allTracks().providerUri(25)
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files",
                PowerampLibraryContract.allTracks().providerUri(
                        PowerampLibraryContract.PROVIDER_ALL_ROWS
                )
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/artists/12/files?lim=7",
                PowerampLibraryContract.artistTracks(12L).providerUri(7)
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/folders_hier/0/subfolders?lim=1",
                PowerampLibraryContract.childFolders(0L).providerUri(1)
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=10",
                PowerampLibraryContract.search(" Björk live ").providerUri(10)
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/queue/91",
                PowerampLibraryContract.playUri(LibraryItem.PlayTarget.queueEntry(91L))
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/playlists/8/files/22",
                PowerampLibraryContract.playUri(
                        LibraryItem.PlayTarget.playlistEntry(8L, 22L)
                )
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.aa/files/44",
                PowerampLibraryContract.albumArtUri(44L)
        );
    }

    @Test
    public void rejectsArbitraryMalformedAndUndocumentedUris() {
        String[] rejectedProviderUris = {
                "file:///music/a.flac",
                "https://example.test/a.flac",
                "content://other.provider/files?lim=10",
                "content://com.maxmpz.audioplayer.data/../files?lim=10",
                "content://com.maxmpz.audioplayer.data/files%2F1?lim=10",
                "content://com.maxmpz.audioplayer.data/files?lim=0",
                "content://com.maxmpz.audioplayer.data/files?lim=1002",
                "content://com.maxmpz.audioplayer.data/files?lim=10&offset=2",
                "content://com.maxmpz.audioplayer.data/files?lim=10&lim=11",
                "content://com.maxmpz.audioplayer.data/files?flt=x&lim=10",
                "content://com.maxmpz.audioplayer.data/search?flt=x&lim=10",
                "content://com.maxmpz.audioplayer.data/search?lim=10",
                "content://com.maxmpz.audioplayer.data/search?flt=%GG&lim=10",
                "content://com.maxmpz.audioplayer.data/search?flt=%C3%28&lim=10",
                "content://user@com.maxmpz.audioplayer.data/files?lim=10"
        };
        for (String uri : rejectedProviderUris) {
            assertFalse(uri, PowerampLibraryContract.isAllowedProviderUri(uri));
        }

        String[] rejectedPlayUris = {
                "file:///music/a.flac",
                "https://example.test/a.flac",
                "content://other.provider/files/1",
                "content://com.maxmpz.audioplayer.data/files",
                "content://com.maxmpz.audioplayer.data/files/0",
                "content://com.maxmpz.audioplayer.data/queue/1?lim=1",
                "content://com.maxmpz.audioplayer.data/search/1",
                "content://com.maxmpz.audioplayer.data/folders_hier/1"
        };
        for (String uri : rejectedPlayUris) {
            assertFalse(uri, PowerampLibraryContract.isAllowedPlayUri(uri));
        }
        assertFalse(PowerampLibraryContract.isAllowedArtworkUri(
                "content://com.maxmpz.audioplayer.aa/albums/4"
        ));
    }

    @Test
    public void validatesIdsSearchAndProviderLimit() {
        assertEquals(7L, PowerampLibraryContract.parsePositiveId("7"));
        assertEquals(0L, PowerampLibraryContract.parseNonNegativeId("0"));
        expectInvalid(() -> PowerampLibraryContract.parsePositiveId("0"));
        expectInvalid(() -> PowerampLibraryContract.parsePositiveId("01"));
        expectInvalid(() -> PowerampLibraryContract.parsePositiveId("-1"));
        expectInvalid(() -> PowerampLibraryContract.parsePositiveId("1/2"));
        expectInvalid(() -> PowerampLibraryContract.parsePositiveId("9223372036854775808"));
        expectInvalid(() -> PowerampLibraryContract.validSearchQuery("   "));
        expectInvalid(() -> PowerampLibraryContract.validSearchQuery("bad\nquery"));
        assertTrue(PowerampLibraryContract.isAllowedProviderUri(
                PowerampLibraryContract.allTracks().providerUri(0)
        ));
        assertTrue(PowerampLibraryContract.isAllowedProviderUri(
                PowerampLibraryContract.queue().providerUri(100)
        ));
    }

    @Test
    public void searchUsesFilesProjectionAndBoundEscapedSelectionArguments() {
        PowerampLibraryContract.Query search = PowerampLibraryContract.search(
                "  rare%_! song  "
        );

        assertArrayEquals(
                PowerampLibraryContract.allTracks().projection(),
                search.projection()
        );
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=17",
                search.providerUri(17)
        );
        assertEquals(
                "(title_tag LIKE ? ESCAPE '!'"
                        + " OR folder_files.name LIKE ? ESCAPE '!'"
                        + " OR artist LIKE ? ESCAPE '!'"
                        + " OR album LIKE ? ESCAPE '!')",
                search.selection()
        );
        assertArrayEquals(
                new String[]{
                        "%rare!%!_!! song%",
                        "%rare!%!_!! song%",
                        "%rare!%!_!! song%",
                        "%rare!%!_!! song%"
                },
                search.selectionArgs()
        );
        assertFalse(search.selection().contains("rare"));
        assertNull(PowerampLibraryContract.allTracks().selection());
        assertNull(PowerampLibraryContract.allTracks().selectionArgs());
        assertFalse(PowerampLibraryContract.isAllowedProviderUri(
                "content://com.maxmpz.audioplayer.data/search?flt=needle&lim=17"
        ));
        java.util.List<String> projection = java.util.Arrays.asList(
                PowerampLibraryContract.allTracks().projection()
        );
        assertTrue(projection.contains(
                "folder_files.created_at AS date_added_epoch_seconds"
        ));
        assertTrue(projection.contains("folder_files.played_times AS play_count"));
    }

    @Test
    public void paginationIdentityIncludesCompleteSortSelection() {
        PowerampLibraryContract.Query ascending = PowerampLibraryContract.allTracks()
                .withSort(new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.ASCENDING
                ));
        PowerampLibraryContract.Query descending = PowerampLibraryContract.allTracks()
                .withSort(new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.DESCENDING
                ));
        assertFalse(ascending.paginationKey().equals(descending.paginationKey()));
        assertFalse(ascending.paginationKey().equals(
                PowerampLibraryContract.albumTracks(1L).withSort(
                        new LibrarySort(
                                LibrarySort.Criterion.TITLE,
                                LibrarySort.Direction.ASCENDING
                        )
                ).paginationKey()
        ));
        expectInvalid(() -> PowerampLibraryContract.artists().withSort(
                new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.ASCENDING
                )
        ));
    }

    @Test
    public void categorizedSearchUsesTitleEntityNamesAndPublicMultiArtistRelation() {
        PowerampLibraryContract.Query tracks =
                PowerampLibraryContract.categorizedTrackTitles("100%_!");
        assertEquals("title_tag LIKE ? ESCAPE '!'", tracks.selection());
        assertArrayEquals(new String[]{"%100!%!_!!%"}, tracks.selectionArgs());

        PowerampLibraryContract.Query exact =
                PowerampLibraryContract.categorizedExactTrackTitles("100%_!");
        assertArrayEquals(new String[]{"100!%!_!!"}, exact.selectionArgs());

        PowerampLibraryContract.Query artists =
                PowerampLibraryContract.relatedArtists(java.util.List.of(41L, 42L));
        assertEquals(
                "EXISTS (SELECT 1 FROM multi_artists"
                        + " WHERE multi_artists.artist_id=artists._id"
                        + " AND multi_artists.file_id IN (?,?))",
                artists.selection()
        );
        assertArrayEquals(new String[]{"41", "42"}, artists.selectionArgs());
        assertEquals(
                "content://com.maxmpz.audioplayer.data/artists?lim=25",
                artists.providerUri(25)
        );

        PowerampLibraryContract.Query membership =
                PowerampLibraryContract.artistMemberTracks(8L);
        assertEquals(
                "EXISTS (SELECT 1 FROM multi_artists"
                        + " WHERE multi_artists.file_id=folder_files._id"
                        + " AND multi_artists.artist_id=?)",
                membership.selection()
        );
        assertArrayEquals(new String[]{"8"}, membership.selectionArgs());
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=25",
                membership.providerUri(25)
        );
        assertFalse(membership.paginationKey().equals(
                PowerampLibraryContract.artistMemberTracks(9L).paginationKey()
        ));

        PowerampLibraryContract.Query albums =
                PowerampLibraryContract.relatedAlbums(java.util.List.of(8L, 9L));
        assertEquals(
                "EXISTS (SELECT 1 FROM folder_files"
                        + " INNER JOIN multi_artists"
                        + " ON multi_artists.file_id=folder_files._id"
                        + " WHERE folder_files.album_id=albums._id"
                        + " AND multi_artists.artist_id IN (?,?))",
                albums.selection()
        );
        assertArrayEquals(new String[]{"8", "9"}, albums.selectionArgs());

        PowerampLibraryContract.Query structuredTracks =
                PowerampLibraryContract.categorizedTrackTitlesForArtists(
                        "rare%_!", java.util.List.of(8L, 9L)
                );
        assertEquals(
                "(title_tag LIKE ? ESCAPE '!') AND EXISTS (SELECT 1 FROM multi_artists"
                        + " WHERE multi_artists.file_id=folder_files._id"
                        + " AND multi_artists.artist_id IN (?,?))",
                structuredTracks.selection()
        );
        assertArrayEquals(
                new String[]{"%rare!%!_!!%", "8", "9"},
                structuredTracks.selectionArgs()
        );

        PowerampLibraryContract.Query structuredAlbums =
                PowerampLibraryContract.categorizedAlbumsForArtists(
                        "record", java.util.List.of(8L)
                );
        assertTrue(structuredAlbums.selection().contains(
                "multi_artists.file_id=folder_files._id"
        ));
        assertTrue(structuredAlbums.selection().contains(
                "folder_files.album_id=albums._id"
        ));
        assertArrayEquals(new String[]{"%record%", "8"}, structuredAlbums.selectionArgs());
        assertTrue(java.util.Arrays.asList(
                PowerampLibraryContract.categorizedArtists("artist").projection()
        ).contains("artists.is_unsplit AS artist_is_unsplit"));
        assertEquals("content://com.maxmpz.audioplayer.data/queue",
                PowerampLibraryContract.queueMutationUri());
        assertEquals("MAX(sort)", PowerampLibraryContract.QUEUE_MAX_SORT_EXPRESSION);
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
