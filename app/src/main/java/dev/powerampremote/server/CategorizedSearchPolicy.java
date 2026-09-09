package dev.powerampremote.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure title-ranking, stable-ID deduplication, and section-order policy. */
final class CategorizedSearchPolicy {
    static final class TrackSelection {
        final boolean exact;
        final List<LibraryItem> matches;

        TrackSelection(boolean exact, List<LibraryItem> matches) {
            this.exact = exact;
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
        }
    }

    static TrackSelection selectTracks(String query, List<LibraryItem> candidates) {
        String normalizedQuery = normalize(query);
        LinkedHashMap<Long, LibraryItem> exact = new LinkedHashMap<>();
        LinkedHashMap<Long, LibraryItem> partial = new LinkedHashMap<>();
        for (LibraryItem item : candidates) {
            if (item == null || item.type != LibraryItem.Type.TRACK || item.title == null) {
                continue;
            }
            String title = normalize(item.title);
            if (title.equals(normalizedQuery)) {
                exact.putIfAbsent(item.id, item);
            } else if (title.contains(normalizedQuery)) {
                partial.putIfAbsent(item.id, item);
            }
        }
        return exact.isEmpty()
                ? new TrackSelection(false, new ArrayList<>(partial.values()))
                : new TrackSelection(true, new ArrayList<>(exact.values()));
    }

    static CategorizedSearch compose(
            String query,
            int limit,
            TrackSelection trackSelection,
            boolean tracksSourceTruncated,
            List<LibraryItem> directArtists,
            boolean directArtistsSourceTruncated,
            List<LibraryItem> relatedArtists,
            boolean relatedArtistsSourceTruncated,
            List<LibraryItem> albums,
            boolean albumsSourceTruncated
    ) {
        if (limit < 1 || limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid categorized Search limit");
        }
        String cleanQuery = PowerampLibraryContract.validSearchQuery(query);
        List<CategorizedSearch.Section> sections = new ArrayList<>(3);

        addSection(
                sections,
                CategorizedSearch.SectionType.TRACKS,
                trackSelection.matches,
                limit,
                tracksSourceTruncated
        );

        LinkedHashMap<Long, LibraryItem> artistMatches = new LinkedHashMap<>();
        addRankedNameMatches(
                artistMatches,
                cleanQuery,
                directArtists,
                LibraryItem.Type.ARTIST
        );
        for (LibraryItem item : relatedArtists) {
            if (item != null && item.type == LibraryItem.Type.ARTIST) {
                artistMatches.putIfAbsent(item.id, item);
            }
        }
        addSection(
                sections,
                CategorizedSearch.SectionType.ARTISTS,
                new ArrayList<>(artistMatches.values()),
                limit,
                directArtistsSourceTruncated || relatedArtistsSourceTruncated
        );

        LinkedHashMap<Long, LibraryItem> albumMatches = new LinkedHashMap<>();
        addRankedNameMatches(albumMatches, cleanQuery, albums, LibraryItem.Type.ALBUM);
        addSection(
                sections,
                CategorizedSearch.SectionType.ALBUMS,
                new ArrayList<>(albumMatches.values()),
                limit,
                albumsSourceTruncated
        );

        CategorizedSearch.TrackMatch trackMatch = trackSelection.matches.isEmpty()
                ? CategorizedSearch.TrackMatch.NONE
                : trackSelection.exact
                        ? CategorizedSearch.TrackMatch.EXACT
                        : CategorizedSearch.TrackMatch.PARTIAL;
        return new CategorizedSearch(cleanQuery, limit, trackMatch, sections);
    }

    private static void addRankedNameMatches(
            Map<Long, LibraryItem> target,
            String query,
            List<LibraryItem> candidates,
            LibraryItem.Type type
    ) {
        String normalizedQuery = normalize(query);
        for (LibraryItem item : candidates) {
            if (item != null && item.type == type && item.title != null
                    && normalize(item.title).equals(normalizedQuery)) {
                target.putIfAbsent(item.id, item);
            }
        }
        for (LibraryItem item : candidates) {
            if (item != null && item.type == type && item.title != null) {
                String title = normalize(item.title);
                if (!title.equals(normalizedQuery) && title.contains(normalizedQuery)) {
                    target.putIfAbsent(item.id, item);
                }
            }
        }
    }

    private static void addSection(
            List<CategorizedSearch.Section> sections,
            CategorizedSearch.SectionType type,
            List<LibraryItem> matches,
            int limit,
            boolean sourceTruncated
    ) {
        if (matches.isEmpty()) {
            return;
        }
        int end = Math.min(limit, matches.size());
        sections.add(new CategorizedSearch.Section(
                type,
                matches.subList(0, end),
                sourceTruncated || matches.size() > limit
        ));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private CategorizedSearchPolicy() {
    }
}
