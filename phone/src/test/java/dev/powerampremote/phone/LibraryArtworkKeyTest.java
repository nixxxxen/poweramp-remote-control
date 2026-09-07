package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class LibraryArtworkKeyTest {
    private static final String SERVER_A = "AAECAwQFBgcICQoLDA0ODw";
    private static final String SERVER_B = "AQIDBAUGBwgJCgsMDQ4PEA";

    @Test
    public void identityUsesOnlyStableServerAndArtworkPath() {
        LibraryArtworkKey first = LibraryArtworkKey.create(
                SERVER_A, "/api/v1/library/artwork/tracks/42"
        );
        LibraryArtworkKey same = LibraryArtworkKey.create(
                SERVER_A, "/api/v1/library/artwork/tracks/42"
        );

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, LibraryArtworkKey.create(
                SERVER_B, "/api/v1/library/artwork/tracks/42"
        ));
        assertNotEquals(first, LibraryArtworkKey.create(
                SERVER_A, "/api/v1/library/artwork/tracks/43"
        ));
        assertTrue(first.diskFileName().matches("[0-9a-f]{64}\\.thumb"));
        assertFalse(first.diskFileName().contains(SERVER_A));
    }

    @Test
    public void rejectsNonLibraryArtworkPaths() {
        assertInvalid(SERVER_A, "http://192.168.1.2/artwork/42");
        assertInvalid(SERVER_A, "/api/v1/artwork");
        assertInvalid(SERVER_A, "/api/v1/library/artwork/tracks/0");
    }

    private static void assertInvalid(String serverId, String path) {
        try {
            LibraryArtworkKey.create(serverId, path);
            fail("Expected invalid key");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
