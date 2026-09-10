package dev.powerampremote.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure entity ranking, canonical-artist filtering, deduplication, and section policy. */
final class CategorizedSearchPolicy {
    private static final int MAX_FUZZY_RESULTS = 5;

    static final class TrackSelection {
        final boolean exact;
        final List<LibraryItem> matches;

        TrackSelection(boolean exact, List<LibraryItem> matches) {
            this.exact = exact;
            this.matches = immutable(matches);
        }
    }

    static final class ArtistSelection {
        final List<LibraryItem> matches;
        final boolean truncated;

        ArtistSelection(List<LibraryItem> matches) {
            this(matches, false);
        }

        ArtistSelection(List<LibraryItem> matches, boolean truncated) {
            this.matches = immutable(matches);
            this.truncated = truncated;
        }

        List<Long> ids() {
            List<Long> ids = new ArrayList<>(matches.size());
            for (LibraryItem match : matches) ids.add(match.id);
            return ids;
        }
    }

    private static final class RankedItem {
        final LibraryItem item;
        final SearchComparisonPolicy.Match match;

        RankedItem(LibraryItem item, SearchComparisonPolicy.Match match) {
            this.item = item;
            this.match = match;
        }
    }

    private static final class RankedItems {
        final List<RankedItem> values;
        final boolean truncated;

        RankedItems(List<RankedItem> values, boolean truncated) {
            this.values = values;
            this.truncated = truncated;
        }
    }

    static TrackSelection selectTracks(String query, List<LibraryItem> candidates) {
        List<RankedItem> ranked = rankedNameMatches(
                query, candidates, LibraryItem.Type.TRACK, false, false
        ).values;
        boolean exact = hasMatchClass(ranked, SearchComparisonPolicy.MatchClass.EXACT);
        List<LibraryItem> selected = new ArrayList<>();
        for (RankedItem value : ranked) {
            selected.add(value.item);
        }
        return new TrackSelection(exact, selected);
    }

    static ArtistSelection selectArtists(
            String query,
            List<LibraryItem> directCandidates,
            List<LibraryItem> exactTrackArtists
    ) {
        List<LibraryItem> canonicalRelated = canonicalArtists(exactTrackArtists);
        RankedItems rankedDirect = rankedNameMatches(
                query, directCandidates, LibraryItem.Type.ARTIST, true, true
        );
        List<RankedItem> direct = rankedDirect.values;
        boolean exact = hasMatchClass(direct, SearchComparisonPolicy.MatchClass.EXACT);
        boolean deterministic = hasDeterministic(direct);
        LinkedHashMap<Long, LibraryItem> selected = new LinkedHashMap<>();
        for (RankedItem value : direct) {
            if (exact && value.match.matchClass != SearchComparisonPolicy.MatchClass.EXACT) {
                continue;
            }
            if (!canonicalRelated.isEmpty()
                    && !deterministic
                    && value.match.matchClass == SearchComparisonPolicy.MatchClass.FUZZY) {
                continue;
            }
            selected.putIfAbsent(value.item.id, value.item.asArtistMembershipTarget());
        }
        for (LibraryItem item : canonicalRelated) {
            selected.putIfAbsent(item.id, item.asArtistMembershipTarget());
        }
        return new ArtistSelection(
                new ArrayList<>(selected.values()),
                rankedDirect.truncated && canonicalRelated.isEmpty()
        );
    }

    static CategorizedSearch compose(
            String query,
            int limit,
            TrackSelection trackSelection,
            boolean tracksSourceTruncated,
            ArtistSelection artistSelection,
            boolean artistsSourceTruncated,
            List<LibraryItem> directAlbums,
            boolean directAlbumsSourceTruncated,
            List<LibraryItem> relatedAlbums,
            boolean relatedAlbumsSourceTruncated
    ) {
        return compose(
                query,
                query,
                limit,
                trackSelection,
                tracksSourceTruncated,
                artistSelection,
                artistsSourceTruncated,
                directAlbums,
                directAlbumsSourceTruncated,
                relatedAlbums,
                relatedAlbumsSourceTruncated
        );
    }

    static CategorizedSearch compose(
            String responseQuery,
            String titleQuery,
            int limit,
            TrackSelection trackSelection,
            boolean tracksSourceTruncated,
            ArtistSelection artistSelection,
            boolean artistsSourceTruncated,
            List<LibraryItem> directAlbums,
            boolean directAlbumsSourceTruncated,
            List<LibraryItem> relatedAlbums,
            boolean relatedAlbumsSourceTruncated
    ) {
        if (limit < 1 || limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid categorized Search limit");
        }
        String cleanQuery = PowerampLibraryContract.validSearchQuery(responseQuery);
        String cleanTitleQuery = PowerampLibraryContract.validSearchQuery(titleQuery);
        List<CategorizedSearch.Section> sections = new ArrayList<>(3);

        addSection(
                sections,
                CategorizedSearch.SectionType.TRACKS,
                trackSelection.matches,
                limit,
                tracksSourceTruncated
        );
        addSection(
                sections,
                CategorizedSearch.SectionType.ARTISTS,
                artistSelection.matches,
                limit,
                artistsSourceTruncated || artistSelection.truncated
        );

        RankedItems albumRanking = rankedNameMatches(
                cleanTitleQuery, directAlbums, LibraryItem.Type.ALBUM, true, false
        );
        List<RankedItem> rankedAlbums = albumRanking.values;
        boolean directAlbumDeterministic = hasDeterministic(rankedAlbums);
        List<LibraryItem> stableRelatedAlbums = deduplicateAndSort(
                relatedAlbums, LibraryItem.Type.ALBUM
        );
        LinkedHashMap<Long, LibraryItem> albumMatches = new LinkedHashMap<>();
        addAlbumRank(
                albumMatches, rankedAlbums, SearchComparisonPolicy.MatchClass.EXACT,
                directAlbumDeterministic, stableRelatedAlbums.isEmpty()
        );
        for (LibraryItem item : stableRelatedAlbums) albumMatches.putIfAbsent(item.id, item);
        addAlbumRank(
                albumMatches, rankedAlbums, SearchComparisonPolicy.MatchClass.PREFIX,
                directAlbumDeterministic, stableRelatedAlbums.isEmpty()
        );
        addAlbumRank(
                albumMatches, rankedAlbums, SearchComparisonPolicy.MatchClass.SUBSTRING,
                directAlbumDeterministic, stableRelatedAlbums.isEmpty()
        );
        addAlbumRank(
                albumMatches, rankedAlbums, SearchComparisonPolicy.MatchClass.FUZZY,
                directAlbumDeterministic, stableRelatedAlbums.isEmpty()
        );
        addSection(
                sections,
                CategorizedSearch.SectionType.ALBUMS,
                new ArrayList<>(albumMatches.values()),
                limit,
                directAlbumsSourceTruncated || relatedAlbumsSourceTruncated
                        || (albumRanking.truncated && stableRelatedAlbums.isEmpty())
        );

        CategorizedSearch.TrackMatch trackMatch = trackSelection.matches.isEmpty()
                ? CategorizedSearch.TrackMatch.NONE
                : trackSelection.exact
                        ? CategorizedSearch.TrackMatch.EXACT
                        : CategorizedSearch.TrackMatch.PARTIAL;
        return new CategorizedSearch(cleanQuery, limit, trackMatch, sections);
    }

    private static void addAlbumRank(
            LinkedHashMap<Long, LibraryItem> target,
            List<RankedItem> ranked,
            SearchComparisonPolicy.MatchClass matchClass,
            boolean hasDeterministic,
            boolean relatedEmpty
    ) {
        if (matchClass == SearchComparisonPolicy.MatchClass.FUZZY
                && (hasDeterministic || !relatedEmpty)) {
            return;
        }
        for (RankedItem value : ranked) {
            if (value.match.matchClass == matchClass) {
                target.putIfAbsent(value.item.id, value.item);
            }
        }
    }

    private static RankedItems rankedNameMatches(
            String query,
            List<LibraryItem> candidates,
            LibraryItem.Type type,
            boolean allowFuzzy,
            boolean canonicalArtistsOnlyUnlessExact
    ) {
        LinkedHashMap<Long, RankedItem> byId = new LinkedHashMap<>();
        if (candidates != null) {
            for (LibraryItem item : candidates) {
                if (item == null || item.type != type || item.title == null) continue;
                SearchComparisonPolicy.Match match = SearchComparisonPolicy.match(
                        query, item.title, allowFuzzy
                );
                if (match.matchClass == SearchComparisonPolicy.MatchClass.NONE) continue;
                if (canonicalArtistsOnlyUnlessExact
                        && Boolean.TRUE.equals(item.artistUnsplit)
                        && match.matchClass != SearchComparisonPolicy.MatchClass.EXACT) {
                    continue;
                }
                RankedItem previous = byId.get(item.id);
                if (previous == null || compareMatches(match, previous.match) < 0) {
                    byId.put(item.id, new RankedItem(item, match));
                }
            }
        }
        List<RankedItem> ranked = new ArrayList<>(byId.values());
        boolean deterministic = hasDeterministic(ranked);
        if (deterministic) {
            ranked.removeIf(item -> item.match.matchClass
                    == SearchComparisonPolicy.MatchClass.FUZZY);
        }
        ranked.sort((left, right) -> {
            int matchOrder = compareMatches(left.match, right.match);
            return matchOrder != 0
                    ? matchOrder : Long.compare(left.item.id, right.item.id);
        });
        boolean truncated = false;
        if (!deterministic) {
            int fuzzyCount = 0;
            for (int index = 0; index < ranked.size();) {
                if (ranked.get(index).match.matchClass == SearchComparisonPolicy.MatchClass.FUZZY
                        && ++fuzzyCount > MAX_FUZZY_RESULTS) {
                    ranked.remove(index);
                    truncated = true;
                } else {
                    index++;
                }
            }
        }
        return new RankedItems(ranked, truncated);
    }

    private static int compareMatches(
            SearchComparisonPolicy.Match left,
            SearchComparisonPolicy.Match right
    ) {
        int classOrder = Integer.compare(left.matchClass.ordinal(), right.matchClass.ordinal());
        return classOrder != 0 ? classOrder : Integer.compare(left.distance, right.distance);
    }

    private static boolean hasMatchClass(
            List<RankedItem> values,
            SearchComparisonPolicy.MatchClass matchClass
    ) {
        for (RankedItem value : values) {
            if (value.match.matchClass == matchClass) return true;
        }
        return false;
    }

    private static boolean hasDeterministic(List<RankedItem> values) {
        for (RankedItem value : values) {
            if (value.match.deterministic()) return true;
        }
        return false;
    }

    private static List<LibraryItem> canonicalArtists(List<LibraryItem> candidates) {
        LinkedHashMap<Long, LibraryItem> byId = new LinkedHashMap<>();
        if (candidates != null) {
            for (LibraryItem item : candidates) {
                if (item != null && item.type == LibraryItem.Type.ARTIST
                        && !Boolean.TRUE.equals(item.artistUnsplit)) {
                    byId.putIfAbsent(item.id, item);
                }
            }
        }
        List<LibraryItem> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparingLong(item -> item.id));
        return result;
    }

    private static List<LibraryItem> deduplicateAndSort(
            List<LibraryItem> candidates,
            LibraryItem.Type type
    ) {
        LinkedHashMap<Long, LibraryItem> byId = new LinkedHashMap<>();
        if (candidates != null) {
            for (LibraryItem item : candidates) {
                if (item != null && item.type == type) byId.putIfAbsent(item.id, item);
            }
        }
        List<LibraryItem> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparingLong(item -> item.id));
        return result;
    }

    private static void addSection(
            List<CategorizedSearch.Section> sections,
            CategorizedSearch.SectionType type,
            List<LibraryItem> matches,
            int limit,
            boolean sourceTruncated
    ) {
        if (matches.isEmpty()) return;
        sections.add(new CategorizedSearch.Section(
                type,
                matches,
                sourceTruncated
        ));
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private CategorizedSearchPolicy() {
    }
}
