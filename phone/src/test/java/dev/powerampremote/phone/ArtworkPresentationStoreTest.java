package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

public final class ArtworkPresentationStoreTest {
    @After
    public void clearStore() {
        ArtworkPresentationStore.retainOnly(null);
    }

    @Test
    public void lruNeverRetainsMoreThanThreeArtworkValues() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "bitmap-a");
        cache.putArtwork("server-a", "art-b", "bitmap-b");
        cache.putArtwork("server-a", "art-c", "bitmap-c");
        assertNotNull(cache.artwork("server-a", "art-a")); // Refresh A in access order.
        cache.putArtwork("server-a", "art-d", "bitmap-d");

        assertEquals(3, cache.bitmapCount());
        assertNull(cache.artwork("server-a", "art-b"));
        assertEquals("bitmap-a", cache.artwork("server-a", "art-a").artwork);
        assertEquals("bitmap-d", cache.artwork("server-a", "art-d").artwork);
    }

    @Test
    public void repeatedIdentityUpdatesValueWithoutGrowingCache() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "first-bitmap");
        cache.putArtwork("server-a", "art-a", "replacement-bitmap");

        assertEquals(1, cache.bitmapCount());
        assertEquals(
                "replacement-bitmap",
                cache.artwork("server-a", "art-a").artwork
        );
    }

    @Test
    public void changingServerClearsBitmapAndAdjacencyNamespaces() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "bitmap-a");
        cache.putArtwork("server-a", "art-b", "bitmap-b");
        cache.confirmNavigation(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT,
                "art-b"
        );

        cache.activateServer("server-b");
        cache.putArtwork("server-b", "art-a", "bitmap-b-server");

        assertNull(cache.artwork("server-a", "art-a"));
        assertNull(cache.neighbor(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));
        assertEquals(
                "bitmap-b-server",
                cache.artwork("server-b", "art-a").artwork
        );
        assertEquals(1, cache.bitmapCount());
        assertEquals(0, cache.adjacencyCount());
    }

    @Test
    public void confirmedNextCreatesForwardAndReverseAdjacency() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "bitmap-a");
        cache.putArtwork("server-a", "art-b", "bitmap-b");
        cache.confirmNavigation(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT,
                "art-b"
        );

        assertEquals(
                "art-b",
                cache.neighbor(
                        "server-a",
                        "art-a",
                        ArtworkNavigationCoordinator.Direction.NEXT
                ).contentIdentity
        );
        assertEquals(
                "art-a",
                cache.neighbor(
                        "server-a",
                        "art-b",
                        ArtworkNavigationCoordinator.Direction.PREVIOUS
                ).contentIdentity
        );
        assertEquals(2, cache.adjacencyCount());
    }

    @Test
    public void previewRequiresExactCurrentIdentityDirectionAndCachedBitmap() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "bitmap-a");
        cache.putArtwork("server-a", "art-b", "bitmap-b");
        cache.confirmNavigation(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT,
                "art-b"
        );

        assertNotNull(cache.neighbor(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));
        assertNull(cache.neighbor(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.PREVIOUS
        ));
        assertNull(cache.neighbor(
                "server-a",
                "unknown-current",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));
        assertNull(cache.neighbor(
                "server-b",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));

        cache.clearAdjacency("server-a");
        assertNull(cache.neighbor(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));
        assertNotNull(cache.artwork("server-a", "art-b"));
    }

    @Test
    public void neutralChangeNeverCreatesAdjacency() {
        ArtworkPresentationStore.Cache<String> cache = cacheFor("server-a");
        cache.putArtwork("server-a", "art-a", "bitmap-a");
        cache.putArtwork("server-a", "art-b", "bitmap-b");
        cache.confirmNavigation(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEUTRAL,
                "art-b"
        );

        assertEquals(0, cache.adjacencyCount());
        assertNull(cache.neighbor(
                "server-a",
                "art-a",
                ArtworkNavigationCoordinator.Direction.NEXT
        ));
    }

    @Test
    public void processStoreRetainsCurrentPresentationOnlyForActiveServer() {
        ArtworkPresentationStore.put("server-a", "art-a", null);
        assertEquals("art-a", ArtworkPresentationStore.get("server-a").contentIdentity);

        ArtworkPresentationStore.put("server-b", "placeholder-b", null);
        assertNull(ArtworkPresentationStore.get("server-a"));
        assertEquals(
                "placeholder-b",
                ArtworkPresentationStore.get("server-b").contentIdentity
        );
    }

    private static ArtworkPresentationStore.Cache<String> cacheFor(String serverIdentity) {
        ArtworkPresentationStore.Cache<String> cache =
                new ArtworkPresentationStore.Cache<>(3);
        cache.activateServer(serverIdentity);
        return cache;
    }
}
