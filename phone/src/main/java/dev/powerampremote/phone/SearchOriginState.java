package dev.powerampremote.phone;

/** Retains the exact Search presentation while a result container is open in Library. */
final class SearchOriginState {
    static boolean supportsDestination(LibraryItem item) {
        return item != null && ("artist".equals(item.type) || "album".equals(item.type));
    }

    static final class Snapshot {
        final String query;
        final CategorizedSearchResult result;
        final int firstVisible;
        final int topOffset;
        final int libraryDepth;

        Snapshot(
                String query,
                CategorizedSearchResult result,
                int firstVisible,
                int topOffset,
                int libraryDepth
        ) {
            this.query = query;
            this.result = result;
            this.firstVisible = firstVisible;
            this.topOffset = topOffset;
            this.libraryDepth = libraryDepth;
        }
    }

    private Snapshot snapshot;

    boolean begin(
            String query,
            CategorizedSearchResult result,
            int firstVisible,
            int topOffset,
            int libraryDepth
    ) {
        if (snapshot != null || result == null || libraryDepth < 1) return false;
        snapshot = new Snapshot(
                query, result, firstVisible, topOffset, libraryDepth
        );
        return true;
    }

    boolean active() {
        return snapshot != null;
    }

    Snapshot peek() {
        return snapshot;
    }

    Snapshot consume() {
        Snapshot value = snapshot;
        snapshot = null;
        return value;
    }

    void clear() {
        snapshot = null;
    }
}
