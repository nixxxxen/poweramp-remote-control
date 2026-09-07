package dev.powerampremote.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class LibraryArtworkDiskCacheTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void evictsOldestEntryWithinByteBudget() throws IOException {
        File directory = temporaryFolder.newFolder("thumbnails");
        LibraryArtworkDiskCache cache = new LibraryArtworkDiskCache(
                directory, 300L, 128, 10_000L
        );
        LibraryArtworkKey first = key(1);
        LibraryArtworkKey second = key(2);
        byte[] payload = new byte[100];

        assertTrue(cache.write(first, payload, 1_000L, 1_000L));
        assertTrue(cache.write(second, payload, 1_001L, 1_001L));

        assertTrue(cache.byteCount() <= 300L);
        assertEquals(1, cache.entryCount());
        assertNull(cache.read(first, 1_002L));
        assertArrayEquals(payload, cache.read(second, 1_002L).bytes);
    }

    @Test
    public void removesCorruptEntryInsteadOfReturningIt() throws IOException {
        File directory = temporaryFolder.newFolder("corrupt");
        LibraryArtworkDiskCache cache = new LibraryArtworkDiskCache(
                directory, 1_024L, 128, 10_000L
        );
        LibraryArtworkKey key = key(9);
        File file = new File(directory, key.diskFileName());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3, 4});
        }

        assertNull(cache.read(key, 2_000L));
        assertFalse(file.exists());
    }

    @Test
    public void exposesFixedProductionBudgets() {
        assertEquals(4L * 1024L * 1024L, LibraryArtworkRepository.MEMORY_BUDGET_BYTES);
        assertEquals(32L * 1024L * 1024L, LibraryArtworkRepository.DISK_BUDGET_BYTES);
        assertEquals(512 * 1024, LibraryArtworkRepository.MAXIMUM_DISK_ENTRY_BYTES);
    }

    private static LibraryArtworkKey key(long trackId) {
        return LibraryArtworkKey.create(
                SERVER_ID, "/api/v1/library/artwork/tracks/" + trackId
        );
    }
}
