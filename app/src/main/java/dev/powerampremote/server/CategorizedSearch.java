package dev.powerampremote.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One typed, independently pageable result from the additive categorized Search route. */
final class CategorizedSearch {
    enum TrackMatch {
        NONE("none"),
        EXACT("exact"),
        PARTIAL("partial");

        final String wireName;

        TrackMatch(String wireName) {
            this.wireName = wireName;
        }
    }

    enum SectionType {
        TRACKS("tracks"),
        ARTISTS("artists"),
        ALBUMS("albums");

        final String wireName;

        SectionType(String wireName) {
            this.wireName = wireName;
        }

        static SectionType fromWireName(String wireName) {
            for (SectionType type : values()) {
                if (type.wireName.equals(wireName)) return type;
            }
            throw new IllegalArgumentException("Unknown Search section");
        }
    }

    static final class Section {
        final SectionType type;
        final List<LibraryItem> items;
        final boolean truncated;
        final String nextPageToken;

        Section(SectionType type, List<LibraryItem> items, boolean truncated) {
            this(type, items, truncated, null);
        }

        Section(
                SectionType type,
                List<LibraryItem> items,
                boolean truncated,
                String nextPageToken
        ) {
            this.type = Objects.requireNonNull(type);
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.truncated = truncated;
            this.nextPageToken = nextPageToken;
        }
    }

    final String query;
    final int limit;
    final TrackMatch trackMatch;
    final List<Section> sections;

    CategorizedSearch(
            String query,
            int limit,
            TrackMatch trackMatch,
            List<Section> sections
    ) {
        this.query = Objects.requireNonNull(query);
        this.limit = limit;
        this.trackMatch = Objects.requireNonNull(trackMatch);
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
    }
}
