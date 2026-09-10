package dev.powerampremote.server;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CategorizedSearchPolicyTest {
    @Test
    public void exactTrackTitlesLeadWithoutSuppressingPartialTitles() {
        CategorizedSearchPolicy.TrackSelection selection =
                CategorizedSearchPolicy.selectTracks("  Of Mice and Men  ", List.of(
                        item(LibraryItem.Type.TRACK, 1L, "Of Mice & Men"),
                        item(LibraryItem.Type.TRACK, 2L, "Of Mice & Men (Live)"),
                        item(LibraryItem.Type.TRACK, 3L, "Elsewhere")
                ));

        assertTrue(selection.exact);
        assertEquals(List.of(1L, 2L), ids(selection.matches));
    }

    @Test
    public void deterministicArtistMatchSuppressesFuzzyFallback() {
        CategorizedSearchPolicy.ArtistSelection selection =
                CategorizedSearchPolicy.selectArtists("Northlane", List.of(
                        artist(8L, "Northlane", false, 12),
                        artist(9L, "Northlame", false, 1)
                ), Collections.emptyList());

        assertEquals(Collections.singletonList(8L), ids(selection.matches));
    }

    @Test
    public void canonicalArtistSuppressesPartialUnsplitRowsAndUsesMembershipBrowse() {
        CategorizedSearchPolicy.ArtistSelection selection =
                CategorizedSearchPolicy.selectArtists("Moe Shop", List.of(
                        artist(10L, "Moe Shop", false, 6),
                        artist(11L, "Moe Shop, KMNZ", true, 1),
                        artist(12L, "Moe Shop, Hentai Dude", true, 1)
                ), Collections.emptyList());

        assertEquals(Collections.singletonList(10L), ids(selection.matches));
        LibraryItem canonical = selection.matches.get(0);
        assertEquals(6, canonical.trackCount.intValue());
        assertNull(canonical.durationMilliseconds);
        assertNotNull(canonical.browseTarget);
        assertEquals(LibraryItem.BrowseTarget.Type.ARTIST_MEMBERSHIP,
                canonical.browseTarget.type);
    }

    @Test
    public void exactFullCompositeArtistMayRemainAResult() {
        CategorizedSearchPolicy.ArtistSelection selection =
                CategorizedSearchPolicy.selectArtists("Moe Shop, KMNZ", List.of(
                        artist(10L, "Moe Shop", false, 6),
                        artist(11L, "Moe Shop, KMNZ", true, 1),
                        artist(12L, "Moe Shop, KMNZ & Friends", true, 1)
                ), Collections.emptyList());

        assertEquals(Collections.singletonList(11L), ids(selection.matches));
    }

    @Test
    public void relatedArtistsAreCanonicalAndDeduplicatedOnlyByStableId() {
        CategorizedSearchPolicy.ArtistSelection selection =
                CategorizedSearchPolicy.selectArtists("Obsidian", Collections.emptyList(), List.of(
                        artist(8L, "Northlane, Guest", true, 1),
                        artist(9L, "Northlane", false, 10),
                        artist(9L, "Renamed duplicate", false, 10),
                        artist(10L, "Guest", false, 2)
                ));

        assertEquals(List.of(9L, 10L), ids(selection.matches));
    }

    @Test
    public void relatedAlbumsFollowExactDirectAndPrecedeRemainingPartialWithIdDeduplication() {
        CategorizedSearch result = CategorizedSearchPolicy.compose(
                "Moe Shop",
                25,
                new CategorizedSearchPolicy.TrackSelection(false, Collections.emptyList()),
                false,
                new CategorizedSearchPolicy.ArtistSelection(Collections.singletonList(
                        artist(10L, "Moe Shop", false, 6).asArtistMembershipTarget()
                )),
                false,
                List.of(
                        item(LibraryItem.Type.ALBUM, 20L, "Moe Shop"),
                        item(LibraryItem.Type.ALBUM, 24L, "Moe Shop Collection")
                ),
                false,
                List.of(
                        item(LibraryItem.Type.ALBUM, 23L, "Pure Pure"),
                        item(LibraryItem.Type.ALBUM, 23L, "Duplicate title")
                ),
                false
        );

        assertEquals(CategorizedSearch.SectionType.ARTISTS, result.sections.get(0).type);
        assertEquals(CategorizedSearch.SectionType.ALBUMS, result.sections.get(1).type);
        assertEquals(List.of(20L, 23L, 24L), ids(result.sections.get(1).items));
    }

    @Test
    public void fixedSectionOrderAndEmptySectionOmissionRemainStable() {
        CategorizedSearchPolicy.TrackSelection tracks =
                CategorizedSearchPolicy.selectTracks("record", Collections.singletonList(
                        item(LibraryItem.Type.TRACK, 1L, "record")
                ));
        CategorizedSearch result = CategorizedSearchPolicy.compose(
                "record",
                25,
                tracks,
                false,
                new CategorizedSearchPolicy.ArtistSelection(Collections.emptyList()),
                false,
                Collections.singletonList(item(LibraryItem.Type.ALBUM, 7L, "Record")),
                false,
                Collections.emptyList(),
                false
        );

        assertEquals(CategorizedSearch.TrackMatch.EXACT, result.trackMatch);
        assertEquals(2, result.sections.size());
        assertEquals(CategorizedSearch.SectionType.TRACKS, result.sections.get(0).type);
        assertEquals(CategorizedSearch.SectionType.ALBUMS, result.sections.get(1).type);
        assertFalse(result.sections.get(1).truncated);
    }

    private static LibraryItem artist(long id, String title, boolean unsplit, int oldCount) {
        return new LibraryItem(
                LibraryItem.Type.ARTIST, id, null, null, title, null, null,
                10_000L, oldCount, null, null, null, unsplit, null
        );
    }

    private static LibraryItem item(LibraryItem.Type type, long id, String title) {
        LibraryItem.PlayTarget play = type == LibraryItem.Type.TRACK
                ? LibraryItem.PlayTarget.track(id)
                : type == LibraryItem.Type.ALBUM
                        ? LibraryItem.PlayTarget.album(id)
                        : null;
        return new LibraryItem(
                type, id, null, null, title, null, null,
                null, null, null, play, null
        );
    }

    private static List<Long> ids(List<LibraryItem> items) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (LibraryItem item : items) ids.add(item.id);
        return ids;
    }
}
