package dev.powerampremote.phone;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Process-local, Server-scoped presentation history with at most three artwork bitmaps. */
final class ArtworkPresentationStore {
    static final int MAX_BITMAP_COUNT = 3;

    static final class Entry {
        final String serverIdentity;
        final String contentIdentity;
        final Bitmap artwork;

        private Entry(String serverIdentity, String contentIdentity, Bitmap artwork) {
            this.serverIdentity = serverIdentity;
            this.contentIdentity = contentIdentity;
            this.artwork = artwork;
        }
    }

    static final class Snapshot<T> {
        final String serverIdentity;
        final String contentIdentity;
        final T artwork;

        private Snapshot(String serverIdentity, String contentIdentity, T artwork) {
            this.serverIdentity = serverIdentity;
            this.contentIdentity = contentIdentity;
            this.artwork = artwork;
        }
    }

    /** Generic pure cache core so LRU/adjacency policy stays JVM-testable without Bitmap creation. */
    static final class Cache<T> {
        private final int bitmapLimit;
        private final LinkedHashMap<ContentKey, T> artworks =
                new LinkedHashMap<>(4, 0.75f, true);
        private final Map<NeighborKey, String> adjacency = new HashMap<>();
        private String activeServerIdentity;
        private String currentContentIdentity;

        Cache(int bitmapLimit) {
            this.bitmapLimit = Math.max(1, bitmapLimit);
        }

        void activateServer(String serverIdentity) {
            if (Objects.equals(activeServerIdentity, serverIdentity)) return;
            activeServerIdentity = serverIdentity;
            currentContentIdentity = null;
            artworks.clear();
            adjacency.clear();
        }

        Snapshot<T> current(String serverIdentity) {
            if (!isActive(serverIdentity) || currentContentIdentity == null) return null;
            return new Snapshot<>(
                    serverIdentity,
                    currentContentIdentity,
                    artworks.get(new ContentKey(serverIdentity, currentContentIdentity))
            );
        }

        Snapshot<T> artwork(String serverIdentity, String contentIdentity) {
            if (!isActive(serverIdentity) || contentIdentity == null) return null;
            T artwork = artworks.get(new ContentKey(serverIdentity, contentIdentity));
            return artwork == null
                    ? null : new Snapshot<>(serverIdentity, contentIdentity, artwork);
        }

        void setCurrent(String serverIdentity, String contentIdentity, T artwork) {
            if (serverIdentity == null || contentIdentity == null) return;
            if (!isActive(serverIdentity)) activateServer(serverIdentity);
            currentContentIdentity = contentIdentity;
            if (artwork != null) putArtwork(serverIdentity, contentIdentity, artwork);
        }

        void putArtwork(String serverIdentity, String contentIdentity, T artwork) {
            if (serverIdentity == null || contentIdentity == null || artwork == null) return;
            if (!isActive(serverIdentity)) activateServer(serverIdentity);
            artworks.put(new ContentKey(serverIdentity, contentIdentity), artwork);
            trimToLimit();
        }

        void confirmNavigation(
                String serverIdentity,
                String fromContentIdentity,
                ArtworkNavigationCoordinator.Direction direction,
                String toContentIdentity
        ) {
            if (!isActive(serverIdentity)
                    || fromContentIdentity == null
                    || toContentIdentity == null
                    || Objects.equals(fromContentIdentity, toContentIdentity)
                    || !isDirectional(direction)) {
                return;
            }
            ArtworkNavigationCoordinator.Direction reverse = reverse(direction);
            removePair(serverIdentity, fromContentIdentity, direction);
            removePair(serverIdentity, toContentIdentity, reverse);
            adjacency.put(
                    new NeighborKey(serverIdentity, fromContentIdentity, direction),
                    toContentIdentity
            );
            adjacency.put(
                    new NeighborKey(serverIdentity, toContentIdentity, reverse),
                    fromContentIdentity
            );
        }

        Snapshot<T> neighbor(
                String serverIdentity,
                String currentIdentity,
                ArtworkNavigationCoordinator.Direction direction
        ) {
            if (!isActive(serverIdentity)
                    || currentIdentity == null
                    || !isDirectional(direction)) {
                return null;
            }
            String neighborIdentity = adjacency.get(
                    new NeighborKey(serverIdentity, currentIdentity, direction)
            );
            return artwork(serverIdentity, neighborIdentity);
        }

        void clearAdjacency(String serverIdentity) {
            if (isActive(serverIdentity)) adjacency.clear();
        }

        int bitmapCount() {
            return artworks.size();
        }

        int adjacencyCount() {
            return adjacency.size();
        }

        private void trimToLimit() {
            while (artworks.size() > bitmapLimit) {
                ContentKey eldest = artworks.keySet().iterator().next();
                artworks.remove(eldest);
            }
        }

        private void removePair(
                String serverIdentity,
                String fromIdentity,
                ArtworkNavigationCoordinator.Direction direction
        ) {
            NeighborKey key = new NeighborKey(serverIdentity, fromIdentity, direction);
            String targetIdentity = adjacency.remove(key);
            if (targetIdentity == null) return;
            NeighborKey reverseKey = new NeighborKey(
                    serverIdentity,
                    targetIdentity,
                    reverse(direction)
            );
            if (Objects.equals(fromIdentity, adjacency.get(reverseKey))) {
                adjacency.remove(reverseKey);
            }
        }

        private boolean isActive(String serverIdentity) {
            return serverIdentity != null
                    && Objects.equals(activeServerIdentity, serverIdentity);
        }

        private static boolean isDirectional(ArtworkNavigationCoordinator.Direction direction) {
            return direction == ArtworkNavigationCoordinator.Direction.NEXT
                    || direction == ArtworkNavigationCoordinator.Direction.PREVIOUS;
        }

        private static ArtworkNavigationCoordinator.Direction reverse(
                ArtworkNavigationCoordinator.Direction direction
        ) {
            return direction == ArtworkNavigationCoordinator.Direction.NEXT
                    ? ArtworkNavigationCoordinator.Direction.PREVIOUS
                    : ArtworkNavigationCoordinator.Direction.NEXT;
        }
    }

    private static final class ContentKey {
        private final String serverIdentity;
        private final String contentIdentity;

        private ContentKey(String serverIdentity, String contentIdentity) {
            this.serverIdentity = serverIdentity;
            this.contentIdentity = contentIdentity;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ContentKey)) return false;
            ContentKey other = (ContentKey) value;
            return Objects.equals(serverIdentity, other.serverIdentity)
                    && Objects.equals(contentIdentity, other.contentIdentity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serverIdentity, contentIdentity);
        }
    }

    private static final class NeighborKey {
        private final String serverIdentity;
        private final String contentIdentity;
        private final ArtworkNavigationCoordinator.Direction direction;

        private NeighborKey(
                String serverIdentity,
                String contentIdentity,
                ArtworkNavigationCoordinator.Direction direction
        ) {
            this.serverIdentity = serverIdentity;
            this.contentIdentity = contentIdentity;
            this.direction = direction;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof NeighborKey)) return false;
            NeighborKey other = (NeighborKey) value;
            return Objects.equals(serverIdentity, other.serverIdentity)
                    && Objects.equals(contentIdentity, other.contentIdentity)
                    && direction == other.direction;
        }

        @Override
        public int hashCode() {
            return Objects.hash(serverIdentity, contentIdentity, direction);
        }
    }

    private static final Cache<Bitmap> CACHE = new Cache<>(MAX_BITMAP_COUNT);

    private ArtworkPresentationStore() {
    }

    static synchronized Entry get(String serverIdentity) {
        return entry(CACHE.current(serverIdentity));
    }

    static synchronized Entry getArtwork(String serverIdentity, String contentIdentity) {
        return entry(CACHE.artwork(serverIdentity, contentIdentity));
    }

    static synchronized Entry getNeighbor(
            String serverIdentity,
            String currentIdentity,
            ArtworkNavigationCoordinator.Direction direction
    ) {
        return entry(CACHE.neighbor(serverIdentity, currentIdentity, direction));
    }

    static synchronized void put(
            String serverIdentity,
            String contentIdentity,
            Bitmap artwork
    ) {
        CACHE.setCurrent(serverIdentity, contentIdentity, artwork);
    }

    static synchronized void confirmNavigation(
            String serverIdentity,
            String fromContentIdentity,
            ArtworkNavigationCoordinator.Direction direction,
            String toContentIdentity
    ) {
        CACHE.confirmNavigation(
                serverIdentity,
                fromContentIdentity,
                direction,
                toContentIdentity
        );
    }

    static synchronized void clearAdjacency(String serverIdentity) {
        CACHE.clearAdjacency(serverIdentity);
    }

    static synchronized void retainOnly(String serverIdentity) {
        CACHE.activateServer(serverIdentity);
    }

    private static Entry entry(Snapshot<Bitmap> snapshot) {
        return snapshot == null ? null : new Entry(
                snapshot.serverIdentity,
                snapshot.contentIdentity,
                snapshot.artwork
        );
    }
}
