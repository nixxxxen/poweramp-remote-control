package dev.powerampremote.phone;

import java.util.Collections;
import java.util.List;

/** One bounded Server page and its opaque continuation token. */
final class LibraryPage {
    final String category;
    final int limit;
    final int offset;
    final List<LibraryItem> items;
    final String nextPageToken;
    final boolean truncated;

    LibraryPage(
            String category,
            int limit,
            int offset,
            List<LibraryItem> items,
            String nextPageToken,
            boolean truncated
    ) {
        this.category = category;
        this.limit = limit;
        this.offset = offset;
        this.items = Collections.unmodifiableList(items);
        this.nextPageToken = nextPageToken;
        this.truncated = truncated;
    }
}
