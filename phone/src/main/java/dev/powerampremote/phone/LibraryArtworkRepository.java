package dev.powerampremote.phone;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Byte-bounded memory/disk storage for lazily encountered Library thumbnails. */
final class LibraryArtworkRepository {
    static final long MEMORY_BUDGET_BYTES = 4L * 1024L * 1024L;
    static final long DISK_BUDGET_BYTES = 32L * 1024L * 1024L;
    static final int MAXIMUM_DISK_ENTRY_BYTES = 512 * 1024;
    static final long MAXIMUM_AGE_MILLISECONDS = 6L * 60L * 60L * 1000L;
    static final long MISSING_RETRY_MILLISECONDS = 10L * 60L * 1000L;
    static final long TRANSIENT_RETRY_MILLISECONDS = 15L * 1000L;

    enum Suppression { MISSING, TRANSIENT_FAILURE }

    private static final class SuppressionEntry {
        final Suppression suppression;
        final long untilMilliseconds;

        SuppressionEntry(Suppression suppression, long untilMilliseconds) {
            this.suppression = suppression;
            this.untilMilliseconds = untilMilliseconds;
        }
    }

    private static final class MemoryEntry {
        final Bitmap bitmap;
        final long createdAtMilliseconds;

        MemoryEntry(Bitmap bitmap, long createdAtMilliseconds) {
            this.bitmap = bitmap;
            this.createdAtMilliseconds = createdAtMilliseconds;
        }
    }

    private final ByteLruCache<LibraryArtworkKey, MemoryEntry> memory =
            new ByteLruCache<>(MEMORY_BUDGET_BYTES, entry ->
                    Math.max(1L, entry.bitmap.getAllocationByteCount()));
    private final LibraryArtworkDiskCache disk;
    private final Map<LibraryArtworkKey, SuppressionEntry> suppressedUntil = new HashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    LibraryArtworkRepository(Context context) {
        disk = new LibraryArtworkDiskCache(
                new File(context.getCacheDir(), "library_thumbnails_v1"),
                DISK_BUDGET_BYTES,
                MAXIMUM_DISK_ENTRY_BYTES,
                MAXIMUM_AGE_MILLISECONDS
        );
    }

    long epoch() {
        return epoch.get();
    }

    Bitmap memory(LibraryArtworkKey key, long nowMilliseconds) {
        MemoryEntry entry = memory.get(key);
        if (entry == null) return null;
        if (nowMilliseconds - entry.createdAtMilliseconds > MAXIMUM_AGE_MILLISECONDS) {
            memory.remove(key);
            return null;
        }
        return entry.bitmap;
    }

    LibraryArtworkDiskCache.Entry disk(LibraryArtworkKey key, long nowMilliseconds) {
        return disk.read(key, nowMilliseconds);
    }

    void removeDisk(LibraryArtworkKey key) {
        disk.remove(key);
    }

    void storeMemory(
            LibraryArtworkKey key,
            Bitmap bitmap,
            long createdAtMilliseconds,
            long requestEpoch
    ) {
        if (epoch.get() == requestEpoch) {
            memory.put(key, new MemoryEntry(bitmap, createdAtMilliseconds));
        }
    }

    void storeDisk(
            LibraryArtworkKey key,
            byte[] bytes,
            long createdAtMilliseconds,
            long accessAtMilliseconds,
            long requestEpoch
    ) {
        if (epoch.get() != requestEpoch) return;
        disk.write(key, bytes, createdAtMilliseconds, accessAtMilliseconds);
        if (epoch.get() != requestEpoch) disk.remove(key);
    }

    synchronized Suppression suppression(LibraryArtworkKey key, long nowMilliseconds) {
        SuppressionEntry entry = suppressedUntil.get(key);
        if (entry == null) return null;
        if (entry.untilMilliseconds <= nowMilliseconds) {
            suppressedUntil.remove(key);
            return null;
        }
        return entry.suppression;
    }

    synchronized void suppressMissing(LibraryArtworkKey key, long nowMilliseconds) {
        suppressedUntil.put(key, new SuppressionEntry(
                Suppression.MISSING,
                nowMilliseconds + MISSING_RETRY_MILLISECONDS
        ));
    }

    synchronized void suppressTransient(LibraryArtworkKey key, long nowMilliseconds) {
        suppressedUntil.put(key, new SuppressionEntry(
                Suppression.TRANSIENT_FAILURE,
                nowMilliseconds + TRANSIENT_RETRY_MILLISECONDS
        ));
    }

    void forgetAll() {
        epoch.incrementAndGet();
        memory.clear();
        synchronized (this) {
            suppressedUntil.clear();
        }
    }

    void clearDisk() {
        disk.clear();
    }
}
