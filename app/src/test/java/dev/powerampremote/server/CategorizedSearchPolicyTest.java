package dev.powerampremote.server;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CategorizedSearchPolicyTest {
    @Test
    public void exactTrackTitlesDominatePartialTitlesAfterTrimAndCaseNormalization() {
        CategorizedSearchPolicy.TrackSelection selection =
                CategorizedSearchPolicy.selectTracks("  ObSiDiAn  ", List.of(
                        item(LibraryItem.Type.TRACK, 1L, "Obsidian"),
                        item(LibraryItem.Type.TRACK, 2L, "Obsidian (Live)"),
                        item(LibraryItem.Type.TRACK, 3L, " obsidian ")
                ));

        assertTrue(selection.exact);
        assertEquals(List.of(1L, 3L), List.of(
                selection.matches.get(0).id,
                selection.matches.get(1).id
        ));
    }

    @Test
    public void partialTrackTitlesRemainWhenThereIsNoExactTitle() {
        CategorizedSearchPolicy.TrackSelection selection =
                CategorizedSearchPolicy.selectTracks("sid", List.of(
                        item(LibraryItem.Type.TRACK, 1L, "Obsidian"),
                        item(LibraryItem.Type.TRACK, 2L, "Elsewhere")
                ));

        assertFalse(selection.exact);
        assertEquals(1, selection.matches.size());
        assertEquals(1L, selection.matches.get(0).id);
    }

    @Test
    public void artistsUnionDirectAndRelatedRowsAndDeduplicateOnlyById() {
        CategorizedSearch result = CategorizedSearchPolicy.compose(
                "Obsidian",
                25,
                CategorizedSearchPolicy.selectTracks("Obsidian", Collections.singletonList(
                        item(LibraryItem.Type.TRACK, 1L, "Obsidian")
                )),
                false,
                List.of(
                        item(LibraryItem.Type.ARTIST, 10L, "Obsidian Choir"),
                        item(LibraryItem.Type.ARTIST, 12L, "Obsidian Duo")
                ),
                false,
                List.of(
                        item(LibraryItem.Type.ARTIST, 10L, "Renamed duplicate"),
                        item(LibraryItem.Type.ARTIST, 11L, "Northlane"),
                        item(LibraryItem.Type.ARTIST, 13L, "Obsidian Duo")
                ),
                false,
                Collections.singletonList(item(LibraryItem.Type.ALBUM, 20L, "Obsidian")),
                false
        );

        assertEquals(CategorizedSearch.SectionType.TRACKS, result.sections.get(0).type);
        assertEquals(CategorizedSearch.SectionType.ARTISTS, result.sections.get(1).type);
        assertEquals(CategorizedSearch.SectionType.ALBUMS, result.sections.get(2).type);
        assertEquals(List.of(10L, 12L, 11L, 13L), List.of(
                result.sections.get(1).items.get(0).id,
                result.sections.get(1).items.get(1).id,
                result.sections.get(1).items.get(2).id,
                result.sections.get(1).items.get(3).id
        ));
    }

    @Test
    public void emptySectionsAreOmittedWithoutChangingFixedOrder() {
        CategorizedSearch result = CategorizedSearchPolicy.compose(
                "record",
                25,
                new CategorizedSearchPolicy.TrackSelection(false, Collections.emptyList()),
                false,
                Collections.emptyList(),
                false,
                Collections.emptyList(),
                false,
                Collections.singletonList(item(LibraryItem.Type.ALBUM, 7L, "Record")),
                false
        );

        assertEquals(CategorizedSearch.TrackMatch.NONE, result.trackMatch);
        assertEquals(1, result.sections.size());
        assertEquals(CategorizedSearch.SectionType.ALBUMS, result.sections.get(0).type);
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
}
