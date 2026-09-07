package dev.powerampremote.phone;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Private encoded-thumbnail disk LRU with a bounded, validated entry format. */
final class LibraryArtworkDiskCache {
    static final class Entry {
        final byte[] bytes;
        final long createdAtMilliseconds;

        Entry(byte[] bytes, long createdAtMilliseconds) {
            this.bytes = bytes;
            this.createdAtMilliseconds = createdAtMilliseconds;
        }
    }

    private static final int MAGIC = 0x50415254;
    private static final int VERSION = 1;
    private static final int MAX_HEADER_BYTES = 512;

    private final File directory;
    private final long maximumBytes;
    private final int maximumEntryBytes;
    private final long maximumAgeMilliseconds;

    LibraryArtworkDiskCache(
            File directory,
            long maximumBytes,
            int maximumEntryBytes,
            long maximumAgeMilliseconds
    ) {
        if (directory == null || maximumBytes <= 0L || maximumEntryBytes <= 0
                || maximumAgeMilliseconds <= 0L) {
            throw new IllegalArgumentException("Invalid disk cache policy");
        }
        this.directory = directory;
        this.maximumBytes = maximumBytes;
        this.maximumEntryBytes = maximumEntryBytes;
        this.maximumAgeMilliseconds = maximumAgeMilliseconds;
    }

    synchronized Entry read(LibraryArtworkKey key, long nowMilliseconds) {
        File file = fileFor(key);
        if (!file.isFile()) return null;
        if (file.length() > maximumEntryBytes + MAX_HEADER_BYTES) {
            delete(file);
            return null;
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Invalid thumbnail header");
            }
            long createdAt = input.readLong();
            if (createdAt <= 0L || createdAt > nowMilliseconds + maximumAgeMilliseconds
                    || nowMilliseconds - createdAt > maximumAgeMilliseconds) {
                throw new IOException("Expired thumbnail");
            }
            if (!key.stableValue().equals(input.readUTF())) {
                throw new IOException("Thumbnail key mismatch");
            }
            int byteCount = input.readInt();
            if (byteCount <= 0 || byteCount > maximumEntryBytes) {
                throw new IOException("Invalid thumbnail length");
            }
            byte[] bytes = new byte[byteCount];
            input.readFully(bytes);
            if (input.read() != -1) throw new IOException("Trailing thumbnail data");
            if (!file.setLastModified(nowMilliseconds)) {
                // A failed access-time touch only weakens LRU precision; the entry is still valid.
            }
            return new Entry(bytes, createdAt);
        } catch (EOFException exception) {
            delete(file);
            return null;
        } catch (IOException | RuntimeException exception) {
            delete(file);
            return null;
        }
    }

    synchronized boolean write(
            LibraryArtworkKey key,
            byte[] bytes,
            long createdAtMilliseconds,
            long accessAtMilliseconds
    ) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumEntryBytes
                || createdAtMilliseconds <= 0L) return false;
        if (!directory.isDirectory() && !directory.mkdirs()) return false;
        File target = fileFor(key);
        File temporary = new File(directory, key.diskFileName() + ".tmp");
        try (FileOutputStream fileOutput = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(fileOutput)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(createdAtMilliseconds);
            output.writeUTF(key.stableValue());
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
            fileOutput.getFD().sync();
        } catch (IOException | RuntimeException exception) {
            delete(temporary);
            return false;
        }
        if (target.exists() && !target.delete()) {
            delete(temporary);
            return false;
        }
        if (!temporary.renameTo(target)) {
            delete(temporary);
            return false;
        }
        if (!target.setLastModified(accessAtMilliseconds)) {
            // Creation time remains a deterministic eviction fallback.
        }
        trimToBudget();
        return target.isFile();
    }

    synchronized void remove(LibraryArtworkKey key) {
        delete(fileFor(key));
    }

    synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().endsWith(".thumb") || file.getName().endsWith(".tmp")) {
                delete(file);
            }
        }
    }

    synchronized long byteCount() {
        File[] files = thumbnailFiles();
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    synchronized int entryCount() {
        return thumbnailFiles().length;
    }

    private void trimToBudget() {
        File[] files = thumbnailFiles();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        for (File file : files) {
            if (total <= maximumBytes) break;
            long length = Math.max(0L, file.length());
            if (delete(file)) total -= length;
        }
    }

    private File[] thumbnailFiles() {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().endsWith(".thumb"));
        return files == null ? new File[0] : files;
    }

    private File fileFor(LibraryArtworkKey key) {
        return new File(directory, key.diskFileName());
    }

    private static boolean delete(File file) {
        return !file.exists() || file.delete();
    }
}
