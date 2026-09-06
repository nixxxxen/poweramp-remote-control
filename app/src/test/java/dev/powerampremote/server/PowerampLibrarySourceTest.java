package dev.powerampremote.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PowerampLibrarySourceTest {
    private FakeProvider provider;
    private PowerampLibrarySource source;
    private AtomicLong clock;

    @Before
    public void setUp() {
        provider = new FakeProvider();
        clock = new AtomicLong(1_000L);
        source = new PowerampLibrarySource(
                provider,
                state -> { },
                clock::get,
                new SecureRandom()
        );
    }

    @After
    public void tearDown() {
        source.close();
    }

    @Test
    public void parsesAllDocumentedFoundationCategoriesAndNullFields() throws Exception {
        provider.rows.put("tracks", Collections.singletonList(row(
                "track_id", 1L,
                "file_name", "fallback.flac",
                "title", null,
                "artist", "Artist",
                "album", "Album",
                "duration_ms", 10_001L,
                "unexpected", "ignored"
        )));
        LibraryItem track = only(source.query(
                PowerampLibraryContract.allTracks(), 25, null, null, cancellation()
        ));
        assertEquals(LibraryItem.Type.TRACK, track.type);
        assertEquals("fallback.flac", track.title);
        assertEquals(10_001L, track.durationMilliseconds.longValue());
        assertEquals("/api/v1/library/artwork/tracks/1", track.artworkPath);

        provider.rows.put("artists", Collections.singletonList(row(
                "item_id", 2L, "title", "Artist", "track_count", 4L,
                "duration_ms", 20_000L
        )));
        LibraryItem artist = only(source.query(
                PowerampLibraryContract.artists(), 25, null, null, cancellation()
        ));
        assertEquals(LibraryItem.Type.ARTIST, artist.type);
        assertEquals(4, artist.trackCount.intValue());
        assertNull(artist.playTarget);

        provider.rows.put("albums", Collections.singletonList(row(
                "item_id", 3L, "title", "Album", "track_count", 2L
        )));
        LibraryItem album = only(source.query(
                PowerampLibraryContract.albums(), 25, null, null, cancellation()
        ));
        assertEquals(LibraryItem.PlayTarget.Type.ALBUM, album.playTarget.type);
        assertNull(album.durationMilliseconds);

        provider.rows.put("folders", Collections.singletonList(row(
                "item_id", 4L, "title", "Folder", "parent_id", 0L,
                "track_count", 7L
        )));
        LibraryItem folder = only(source.query(
                PowerampLibraryContract.folders(), 25, null, null, cancellation()
        ));
        assertEquals(LibraryItem.Type.FOLDER, folder.type);
        assertEquals(0L, folder.parentId.longValue());

        provider.rows.put("folder_tree", Collections.singletonList(row(
                "item_id", 5L, "title", "Child", "parent_id", 4L,
                "track_count", 9L, "duration_ms", 90_000L
        )));
        LibraryItem hierarchyFolder = only(source.query(
                PowerampLibraryContract.childFolders(4L),
                25,
                null,
                null,
                cancellation()
        ));
        assertEquals(4L, hierarchyFolder.parentId.longValue());
        assertEquals(9, hierarchyFolder.trackCount.intValue());

        provider.rows.put("playlists", Collections.singletonList(row(
                "item_id", 6L, "title", "Playlist", "track_count", 3L,
                "duration_ms", 30_000L
        )));
        LibraryItem playlist = only(source.query(
                PowerampLibraryContract.playlists(), 25, null, null, cancellation()
        ));
        assertEquals(LibraryItem.Type.PLAYLIST, playlist.type);
        assertEquals(LibraryItem.PlayTarget.Type.PLAYLIST, playlist.playTarget.type);

        provider.rows.put("playlist_tracks", Collections.singletonList(row(
                "track_id", 1L, "entry_id", 61L, "title", "Duplicate"
        )));
        LibraryItem playlistEntry = only(source.query(
                PowerampLibraryContract.playlistTracks(6L),
                25,
                null,
                null,
                cancellation()
        ));
        assertEquals(61L, playlistEntry.entryId.longValue());
        assertEquals(6L, playlistEntry.parentId.longValue());

        provider.rows.put("search", Collections.singletonList(row(
                "track_id", 7L, "title", "Found"
        )));
        LibraryItem found = only(source.query(
                PowerampLibraryContract.search("needle"),
                10,
                null,
                null,
                cancellation()
        ));
        assertEquals("Found", found.title);
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=11",
                provider.lastProviderUri
        );

        assertEquals(8, provider.closedRows.get());
    }

    @Test
    public void missingEssentialAndUnexpectedCursorFieldsAreSafe() throws Exception {
        provider.rows.put("tracks", List.of(
                row("title", "Missing ID", "mystery", 1L),
                row("track_id", 8L, "title", null, "artist", null, "mystery", "x")
        ));
        PowerampLibrarySource.Result result = source.query(
                PowerampLibraryContract.allTracks(), 25, null, null, cancellation()
        );
        assertTrue(result.isSuccess());
        assertEquals(1, result.page.items.size());
        assertEquals(8L, result.page.items.get(0).id);
        assertNull(result.page.items.get(0).title);
        assertEquals(1, provider.closedRows.get());
    }

    @Test
    public void paginationIsBoundedOpaqueAndClosesEachCursor() throws Exception {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (long id = 1L; id <= 1_001L; id++) {
            tracks.add(row("track_id", id, "title", "Track " + id));
        }
        provider.rows.put("tracks", tracks);

        String token = null;
        for (int pageIndex = 0; pageIndex < 10; pageIndex++) {
            PowerampLibrarySource.Result result = source.query(
                    PowerampLibraryContract.allTracks(),
                    100,
                    token,
                    null,
                    cancellation()
            );
            assertTrue(result.isSuccess());
            assertEquals(pageIndex * 100, result.page.offset);
            assertEquals(pageIndex * 100L + 1L, result.page.items.get(0).id);
            if (pageIndex < 9) {
                assertNotNull(result.page.nextPageToken);
                assertFalse(result.page.truncated);
            } else {
                assertNull(result.page.nextPageToken);
                assertTrue(result.page.truncated);
            }
            token = result.page.nextPageToken;
        }
        assertEquals(10, provider.closedRows.get());
        assertEquals(101, provider.requestedLimits.get(0).intValue());
        assertEquals(1_001, provider.requestedLimits.get(9).intValue());
        for (int limit : provider.requestedLimits) {
            assertTrue(limit <= PowerampLibraryContract.MAX_CONTINUATION_ROWS + 1);
        }
    }

    @Test
    public void paginationClipsPartialLastPageAtSafetyWindow() throws Exception {
        for (int availableRows : new int[]{999, 1_000, 1_001}) {
            List<Map<String, Object>> tracks = new ArrayList<>();
            for (long id = 1L; id <= availableRows; id++) {
                tracks.add(row("track_id", id));
            }
            provider.rows.put("search", tracks);
            PowerampLibraryContract.Query query = PowerampLibraryContract.search("needle");
            String token = null;
            int delivered = 0;
            do {
                PowerampLibrarySource.Page page = source.query(
                        query, 33, token, null, cancellation()
                ).page;
                assertEquals(33, page.limit);
                assertEquals(delivered, page.offset);
                int expectedCount = Math.min(33, Math.min(availableRows, 1_000) - delivered);
                assertEquals(expectedCount, page.items.size());
                for (LibraryItem item : page.items) {
                    assertEquals(++delivered, item.id);
                }
                token = page.nextPageToken;
                boolean lastPage = delivered == Math.min(availableRows, 1_000);
                assertEquals(lastPage, token == null);
                assertEquals(lastPage && availableRows > 1_000, page.truncated);
            } while (token != null);
            assertEquals(Math.min(availableRows, 1_000), delivered);
        }
    }

    @Test
    public void pageTokenIsBoundToQueryAndExpires() throws Exception {
        provider.rows.put("tracks", List.of(
                row("track_id", 1L),
                row("track_id", 2L)
        ));
        PowerampLibrarySource.Result first = source.query(
                PowerampLibraryContract.allTracks(), 1, null, null, cancellation()
        );
        String token = first.page.nextPageToken;
        assertNotNull(token);

        expectInvalidToken(() -> source.query(
                PowerampLibraryContract.queue(), 1, token, null, cancellation()
        ));
        clock.addAndGet(5L * 60L * 1_000L);
        expectInvalidToken(() -> source.query(
                PowerampLibraryContract.allTracks(), 1, token, null, cancellation()
        ));
    }

    @Test
    public void searchPaginationKeepsBoundedProviderLimitsAndQueryBinding() throws Exception {
        provider.rows.put("search", List.of(
                row("track_id", 1L, "title", "First"),
                row("track_id", 2L, "title", "Second"),
                row("track_id", 3L, "title", "Third")
        ));
        PowerampLibraryContract.Query query = PowerampLibraryContract.search("needle");

        PowerampLibrarySource.Result first = source.query(
                query, 1, null, null, cancellation()
        );
        assertEquals(1L, first.page.items.get(0).id);
        assertNotNull(first.page.nextPageToken);
        expectInvalidToken(() -> source.query(
                PowerampLibraryContract.search("different"),
                1,
                first.page.nextPageToken,
                null,
                cancellation()
        ));

        PowerampLibrarySource.Result second = source.query(
                query, 1, first.page.nextPageToken, null, cancellation()
        );
        assertEquals(1, second.page.offset);
        assertEquals(2L, second.page.items.get(0).id);
        assertEquals(List.of(2, 3), provider.requestedLimits);
        assertEquals(2, provider.closedRows.get());
        assertEquals(
                "content://com.maxmpz.audioplayer.data/files?lim=3",
                provider.lastProviderUri
        );
    }

    @Test
    public void queueKeepsProviderOrderDuplicateEntryIdsAndExactCurrentMatch() throws Exception {
        provider.rows.put("queue", List.of(
                row("track_id", 5L, "entry_id", 90L, "title", "Same"),
                row("track_id", 5L, "entry_id", 91L, "title", "Same")
        ));
        PowerampLibrarySource.Result result = source.query(
                PowerampLibraryContract.queue(), 25, null, 91L, cancellation()
        );
        assertEquals(2, result.page.items.size());
        LibraryItem first = result.page.items.get(0);
        LibraryItem second = result.page.items.get(1);
        assertEquals(5L, first.id);
        assertEquals(5L, second.id);
        assertEquals(90L, first.entryId.longValue());
        assertEquals(91L, second.entryId.longValue());
        assertFalse(first.current);
        assertTrue(second.current);
        assertEquals(LibraryItem.PlayTarget.Type.QUEUE_ENTRY, second.playTarget.type);
        assertEquals(91L, second.playTarget.id);

        PowerampLibrarySource.Result withoutQueueSnapshot = source.query(
                PowerampLibraryContract.queue(), 25, null, null, cancellation()
        );
        assertNull(withoutQueueSnapshot.page.items.get(0).current);
        assertNull(withoutQueueSnapshot.page.items.get(1).current);
    }

    @Test
    public void validatesThatSelectedQueueEntryStillExists() {
        provider.rows.put("play_queue_entry", Collections.singletonList(row(
                "track_id", 5L, "entry_id", 91L, "title", "Queued"
        )));
        PowerampLibrarySource.SelectionResult selected = source.validateSelection(
                LibraryItem.PlayTarget.queueEntry(91L), cancellation()
        );
        assertEquals(LibraryAccessState.Status.AVAILABLE, selected.status);
        assertTrue(selected.exists);

        PowerampLibrarySource.SelectionResult stale = source.validateSelection(
                LibraryItem.PlayTarget.queueEntry(92L), cancellation()
        );
        assertEquals(LibraryAccessState.Status.AVAILABLE, stale.status);
        assertFalse(stale.exists);
        assertEquals(2, provider.closedRows.get());
    }

    @Test
    public void mapsMissingPermissionUnavailableAndProviderExceptionsWithoutLeaking() throws Exception {
        provider.installed = false;
        assertEquals(
                LibraryAccessState.Status.POWERAMP_MISSING,
                source.query(
                        PowerampLibraryContract.allTracks(),
                        1,
                        null,
                        null,
                        cancellation()
                ).status
        );

        provider.installed = true;
        assertFailure(PowerampLibraryProvider.Failure.PERMISSION_REQUIRED,
                LibraryAccessState.Status.PERMISSION_REQUIRED);
        assertFailure(PowerampLibraryProvider.Failure.UNAVAILABLE,
                LibraryAccessState.Status.PROVIDER_UNAVAILABLE);
        assertFailure(PowerampLibraryProvider.Failure.ERROR,
                LibraryAccessState.Status.PROVIDER_ERROR);

        provider.failure = null;
        provider.failOnMove = true;
        PowerampLibrarySource.Result cursorFailure = source.query(
                PowerampLibraryContract.allTracks(), 1, null, null, cancellation()
        );
        assertEquals(LibraryAccessState.Status.PROVIDER_ERROR, cursorFailure.status);
        assertEquals(1, provider.closedRows.get());
    }

    @Test
    public void deadProviderSearchIsUnavailableAndIsNotRetried() throws Exception {
        provider.failure = PowerampLibraryProvider.Failure.UNAVAILABLE;

        PowerampLibrarySource.Result result = source.query(
                PowerampLibraryContract.search("private query"),
                10,
                null,
                null,
                cancellation()
        );

        assertEquals(LibraryAccessState.Status.PROVIDER_UNAVAILABLE, result.status);
        assertNull(result.page);
        assertEquals(1, provider.requestedLimits.size());
        assertEquals(11, provider.requestedLimits.get(0).intValue());
    }

    @Test
    public void cancelledOldSearchCannotReplaceIndependentNewResult() throws Exception {
        CountDownLatch oldStarted = new CountDownLatch(1);
        provider.blockedSearchStarted = oldStarted;
        AtomicReference<PowerampLibrarySource.Result> oldResult = new AtomicReference<>();
        LibraryCancellation oldCancellation = cancellation();
        Thread oldThread = new Thread(() -> {
            try {
                oldResult.set(source.query(
                        PowerampLibraryContract.search("old private query"),
                        10,
                        null,
                        null,
                        oldCancellation
                ));
            } catch (PowerampLibrarySource.InvalidPageTokenException impossible) {
                throw new AssertionError(impossible);
            }
        });
        oldThread.start();
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS));
        oldCancellation.cancel();

        provider.rows.put("search", Collections.singletonList(row(
                "track_id", 20L, "title", "New result"
        )));
        PowerampLibrarySource.Result current = source.query(
                PowerampLibraryContract.search("new query"),
                10,
                null,
                null,
                cancellation()
        );
        oldThread.join(2_000L);

        assertFalse(oldThread.isAlive());
        assertEquals(LibraryAccessState.Status.PROVIDER_ERROR, oldResult.get().status);
        assertEquals("New result", current.page.items.get(0).title);
    }

    private void assertFailure(
            PowerampLibraryProvider.Failure failure,
            LibraryAccessState.Status expected
    ) throws Exception {
        provider.failure = failure;
        PowerampLibrarySource.Result result = source.query(
                PowerampLibraryContract.allTracks(), 1, null, null, cancellation()
        );
        assertEquals(expected, result.status);
        assertNull(result.page);
    }

    private static LibraryItem only(PowerampLibrarySource.Result result) {
        assertTrue(result.isSuccess());
        assertEquals(1, result.page.items.size());
        return result.page.items.get(0);
    }

    private static LibraryCancellation cancellation() {
        return new LibraryCancellation();
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private interface ThrowingQuery {
        void run() throws Exception;
    }

    private static void expectInvalidToken(ThrowingQuery query) throws Exception {
        try {
            query.run();
            fail("Expected InvalidPageTokenException");
        } catch (PowerampLibrarySource.InvalidPageTokenException expected) {
            // Expected.
        }
    }

    private static final class FakeProvider implements PowerampLibraryProvider {
        final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        final List<Integer> requestedLimits = new ArrayList<>();
        final AtomicLong closedRows = new AtomicLong();
        volatile boolean installed = true;
        volatile Failure failure;
        volatile boolean failOnMove;
        volatile CountDownLatch blockedSearchStarted;
        volatile String lastProviderUri;

        @Override
        public boolean isPowerampInstalled() {
            return installed;
        }

        @Override
        public Rows query(
                PowerampLibraryContract.Query query,
                int limit,
                LibraryCancellation cancellation
        ) throws ProviderException {
            requestedLimits.add(limit);
            lastProviderUri = query.providerUri(limit);
            if (failure != null) {
                throw new ProviderException(failure);
            }
            if ("search".equals(query.category)
                    && "old private query".equals(query.filter)
                    && blockedSearchStarted != null) {
                CountDownLatch released = new CountDownLatch(1);
                cancellation.attach(released::countDown);
                blockedSearchStarted.countDown();
                try {
                    if (!released.await(2, TimeUnit.SECONDS)) {
                        throw new ProviderException(Failure.ERROR);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    cancellation.detach();
                }
                throw new ProviderException(Failure.CANCELLED);
            }
            List<Map<String, Object>> available = rows.getOrDefault(
                    query.category,
                    Collections.emptyList()
            );
            List<Map<String, Object>> limited = new ArrayList<>(
                    available.subList(0, Math.min(limit, available.size()))
            );
            return new FakeRows(limited, cancellation, failOnMove, closedRows);
        }
    }

    private static final class FakeRows implements PowerampLibraryProvider.Rows {
        private final List<Map<String, Object>> rows;
        private final LibraryCancellation cancellation;
        private final boolean failOnMove;
        private final AtomicLong closedRows;
        private int index = -1;
        private boolean closed;

        FakeRows(
                List<Map<String, Object>> rows,
                LibraryCancellation cancellation,
                boolean failOnMove,
                AtomicLong closedRows
        ) {
            this.rows = rows;
            this.cancellation = cancellation;
            this.failOnMove = failOnMove;
            this.closedRows = closedRows;
        }

        @Override
        public boolean moveToNext() throws PowerampLibraryProvider.ProviderException {
            if (cancellation.isCancelled()) {
                throw new PowerampLibraryProvider.ProviderException(
                        PowerampLibraryProvider.Failure.CANCELLED
                );
            }
            if (failOnMove) {
                throw new PowerampLibraryProvider.ProviderException(
                        PowerampLibraryProvider.Failure.ERROR
                );
            }
            index++;
            return index < rows.size();
        }

        @Override
        public Long longValue(String column) {
            Object value = rows.get(index).get(column);
            return value instanceof Number ? ((Number) value).longValue() : null;
        }

        @Override
        public String textValue(String column) {
            Object value = rows.get(index).get(column);
            return value instanceof String ? (String) value : null;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closedRows.incrementAndGet();
            }
        }
    }
}
