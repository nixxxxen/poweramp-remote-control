package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
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

        Section(SectionType type, List<LibraryItem> items, boolean truncated) {
            this.type = type;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.truncated = truncated;
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
}
