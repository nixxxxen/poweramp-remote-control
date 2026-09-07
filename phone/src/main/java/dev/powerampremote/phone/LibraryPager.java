package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Accumulates only explicitly requested pages and enforces the opaque continuation chain. */
final class LibraryPager {
    private final List<LibraryItem> items = new ArrayList<>();
    private String nextPageToken;
    private boolean truncated;
    private int expectedOffset;
    private boolean initialized;

    void reset() {
        items.clear();
        nextPageToken = null;
        truncated = false;
        expectedOffset = 0;
        initialized = false;
    }

    void accept(String requestedPageToken, LibraryPage page) {
        if (!initialized) {
            if (requestedPageToken != null || page.offset != 0) {
                throw new IllegalArgumentException("Invalid first page");
            }
            items.clear();
        } else {
            if (nextPageToken == null || !nextPageToken.equals(requestedPageToken)
                    || page.offset != expectedOffset) {
                throw new IllegalArgumentException("Invalid continuation page");
            }
        }
        items.addAll(page.items);
        nextPageToken = page.nextPageToken;
        truncated = page.truncated;
        expectedOffset = page.offset + page.limit;
        initialized = true;
    }

    List<LibraryItem> items() {
        return Collections.unmodifiableList(items);
    }

    String nextPageToken() {
        return nextPageToken;
    }

    boolean canLoadMore() {
        return nextPageToken != null && !truncated;
    }

    boolean truncated() {
        return truncated;
    }

    boolean initialized() {
        return initialized;
    }
}
