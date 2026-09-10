package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Typed response from the additive API v1 categorized Search route. */
final class CategorizedSearchResult {
    enum SectionType {
        TRACKS("tracks", "track"),
        ARTISTS("artists", "artist"),
        ALBUMS("albums", "album");

        final String wireName;
        final String itemType;

        SectionType(String wireName, String itemType) {
            this.wireName = wireName;
            this.itemType = itemType;
        }

        static SectionType fromWireName(String value) {
            for (SectionType type : values()) {
                if (type.wireName.equals(value)) return type;
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
            this.type = type;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.truncated = truncated;
            this.nextPageToken = nextPageToken;
        }
    }

    final String query;
    final int limit;
    final String trackMatch;
    final List<Section> sections;

    CategorizedSearchResult(
            String query,
            int limit,
            String trackMatch,
            List<Section> sections
    ) {
        this.query = query;
        this.limit = limit;
        this.trackMatch = trackMatch;
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
    }

    Section section(SectionType type) {
        for (Section section : sections) {
            if (section.type == type) return section;
        }
        return null;
    }

    boolean truncated() {
        for (Section section : sections) {
            if (section.truncated) return true;
        }
        return false;
    }

    CategorizedSearchResult mergeSection(
            SectionType type,
            CategorizedSearchResult page,
            boolean replace
    ) {
        if (page == null || !query.equals(page.query)
                || (!replace && !trackMatch.equals(page.trackMatch))) {
            throw new IllegalArgumentException("Mismatched Search continuation");
        }
        Section incoming = page.section(type);
        List<Section> merged = new ArrayList<>();
        for (SectionType candidate : SectionType.values()) {
            Section current = section(candidate);
            if (candidate == type) {
                if (incoming == null) {
                    if (!replace && current != null) merged.add(current);
                    continue;
                }
                if (replace || current == null) {
                    merged.add(incoming);
                } else {
                    LinkedHashMap<Long, LibraryItem> byId = new LinkedHashMap<>();
                    for (LibraryItem item : current.items) byId.putIfAbsent(item.id, item);
                    for (LibraryItem item : incoming.items) byId.putIfAbsent(item.id, item);
                    merged.add(new Section(
                            type,
                            new ArrayList<>(byId.values()),
                            incoming.truncated,
                            incoming.nextPageToken
                    ));
                }
            } else if (current != null) {
                merged.add(current);
            }
        }
        String mergedTrackMatch = replace && type == SectionType.TRACKS
                ? page.trackMatch : trackMatch;
        return new CategorizedSearchResult(query, limit, mergedTrackMatch, merged);
    }
}
