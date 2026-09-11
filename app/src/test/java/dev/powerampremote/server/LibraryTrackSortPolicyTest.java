package dev.powerampremote.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class LibraryTrackSortPolicyTest {
    @Test
    public void sortsEverySupportedFieldInBothDirections() {
        List<LibraryItem> source = List.of(
                item(1L, "Alpha", "Zulu", "Beta", 300L, 30L, 3L),
                item(2L, "Beta", "Yankee", "Charlie", 200L, 20L, 2L),
                item(3L, "Gamma", "Xray", "Alpha", 100L, 10L, 1L)
        );

        assertOrder(source, LibrarySort.Criterion.TITLE, 1L, 2L, 3L);
        assertReverseOrder(source, LibrarySort.Criterion.TITLE, 3L, 2L, 1L);
        assertOrder(source, LibrarySort.Criterion.ALBUM, 3L, 2L, 1L);
        assertReverseOrder(source, LibrarySort.Criterion.ALBUM, 1L, 2L, 3L);
        assertOrder(source, LibrarySort.Criterion.ARTIST, 3L, 1L, 2L);
        assertReverseOrder(source, LibrarySort.Criterion.ARTIST, 2L, 1L, 3L);
        assertOrder(source, LibrarySort.Criterion.DURATION, 3L, 2L, 1L);
        assertReverseOrder(source, LibrarySort.Criterion.DURATION, 1L, 2L, 3L);
        assertOrder(source, LibrarySort.Criterion.DATE_ADDED, 3L, 2L, 1L);
        assertReverseOrder(source, LibrarySort.Criterion.DATE_ADDED, 1L, 2L, 3L);
        assertOrder(source, LibrarySort.Criterion.PLAY_COUNT, 3L, 2L, 1L);
        assertReverseOrder(source, LibrarySort.Criterion.PLAY_COUNT, 1L, 2L, 3L);
        assertSame(source, LibraryTrackSortPolicy.sorted(source, LibrarySort.POWERAMP));
    }

    @Test
    public void keepsMissingValuesLastAndUsesUnicodeAwareDeterministicTies() {
        LibraryItem missing = item(9L, null, null, null, null, null, null);
        LibraryItem upper = item(2L, "Sān-Z", "Same", "Same", 5L, 5L, 5L);
        LibraryItem lower = item(1L, "san-z", "Same", "Same", 5L, 5L, 5L);
        List<LibraryItem> source = List.of(missing, upper, lower);

        List<LibraryItem> ascending = LibraryTrackSortPolicy.sorted(
                source,
                new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.ASCENDING
                )
        );
        List<LibraryItem> descending = LibraryTrackSortPolicy.sorted(
                source,
                new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.DESCENDING
                )
        );

        assertEquals(List.of(1L, 2L, 9L), ids(ascending));
        assertEquals(List.of(2L, 1L, 9L), ids(descending));
        for (LibrarySort.Criterion criterion : LibrarySort.Criterion.values()) {
            if (criterion == LibrarySort.Criterion.DEFAULT) continue;
            for (LibrarySort.Direction direction : LibrarySort.Direction.values()) {
                List<LibraryItem> sorted = LibraryTrackSortPolicy.sorted(
                        source, new LibrarySort(criterion, direction)
                );
                assertEquals(
                        criterion + " " + direction,
                        9L,
                        sorted.get(sorted.size() - 1).id
                );
            }
        }

        List<LibraryItem> equalTitles = List.of(
                item(12L, "Equal", "Same", "Same", 5L, 5L, 5L),
                item(11L, "Equal", "Same", "Same", 5L, 5L, 5L)
        );
        assertEquals(
                List.of(11L, 12L),
                ids(LibraryTrackSortPolicy.sorted(
                        equalTitles,
                        new LibrarySort(
                                LibrarySort.Criterion.TITLE,
                                LibrarySort.Direction.ASCENDING
                        )
                ))
        );

        LibraryItem laterEntry = playlistItem(40L, 60L);
        LibraryItem earlierEntry = playlistItem(40L, 50L);
        List<LibraryItem> entries = LibraryTrackSortPolicy.sorted(
                List.of(laterEntry, earlierEntry),
                new LibrarySort(
                        LibrarySort.Criterion.TITLE,
                        LibrarySort.Direction.ASCENDING
                )
        );
        assertEquals(50L, entries.get(0).entryId.longValue());
        assertEquals(60L, entries.get(1).entryId.longValue());
    }

    private static void assertOrder(
            List<LibraryItem> source,
            LibrarySort.Criterion criterion,
            Long... expected
    ) {
        assertEquals(
                List.of(expected),
                ids(LibraryTrackSortPolicy.sorted(
                        source,
                        new LibrarySort(criterion, LibrarySort.Direction.ASCENDING)
                ))
        );
    }

    private static void assertReverseOrder(
            List<LibraryItem> source,
            LibrarySort.Criterion criterion,
            Long... expected
    ) {
        assertEquals(
                List.of(expected),
                ids(LibraryTrackSortPolicy.sorted(
                        source,
                        new LibrarySort(criterion, LibrarySort.Direction.DESCENDING)
                ))
        );
    }

    private static LibraryItem item(
            long id,
            String title,
            String album,
            String artist,
            Long duration,
            Long dateAdded,
            Long playCount
    ) {
        return new LibraryItem(
                LibraryItem.Type.TRACK,
                id,
                null,
                null,
                title,
                artist,
                album,
                duration,
                dateAdded,
                playCount,
                null,
                PowerampLibraryContract.artworkApiPath(id),
                LibraryItem.PlayTarget.track(id),
                null,
                null,
                null
        );
    }

    private static LibraryItem playlistItem(long trackId, long entryId) {
        return new LibraryItem(
                LibraryItem.Type.PLAYLIST_ENTRY,
                trackId,
                entryId,
                7L,
                "Same",
                "Same",
                "Same",
                5L,
                5L,
                5L,
                null,
                PowerampLibraryContract.artworkApiPath(trackId),
                LibraryItem.PlayTarget.playlistEntry(7L, entryId),
                null,
                null,
                null
        );
    }

    private static List<Long> ids(List<LibraryItem> items) {
        ArrayList<Long> ids = new ArrayList<>();
        for (LibraryItem item : items) ids.add(item.id);
        return ids;
    }
}
