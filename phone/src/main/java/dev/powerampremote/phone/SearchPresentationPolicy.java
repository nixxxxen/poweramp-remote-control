package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure fixed-order Search section/header presentation policy. */
final class SearchPresentationPolicy {
    static final class Row {
        final CategorizedSearchResult.SectionType header;
        final LibraryItem item;
        final CategorizedSearchResult.SectionType more;

        private Row(
                CategorizedSearchResult.SectionType header,
                LibraryItem item,
                CategorizedSearchResult.SectionType more
        ) {
            this.header = header;
            this.item = item;
            this.more = more;
        }

        static Row header(CategorizedSearchResult.SectionType section) {
            return new Row(section, null, null);
        }

        static Row item(LibraryItem item) {
            return new Row(null, item, null);
        }

        static Row more(CategorizedSearchResult.SectionType section) {
            return new Row(null, null, section);
        }

        boolean isHeader() {
            return header != null;
        }
    }

    static List<Row> rows(CategorizedSearchResult result) {
        if (result == null) return Collections.emptyList();
        List<Row> rows = new ArrayList<>();
        for (CategorizedSearchResult.SectionType type
                : CategorizedSearchResult.SectionType.values()) {
            CategorizedSearchResult.Section section = result.section(type);
            if (section == null || section.items.isEmpty()) continue;
            rows.add(Row.header(type));
            for (LibraryItem item : section.items) rows.add(Row.item(item));
            if (section.nextPageToken != null) rows.add(Row.more(type));
        }
        return Collections.unmodifiableList(rows);
    }

    private SearchPresentationPolicy() {
    }
}
