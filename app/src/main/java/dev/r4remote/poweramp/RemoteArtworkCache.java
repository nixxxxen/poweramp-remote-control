package dev.r4remote.poweramp;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Encodes the already-loaded Poweramp artwork once for authenticated HTTP delivery. */
final class RemoteArtworkCache implements AutoCloseable {
    private static final int JPEG_QUALITY = 88;
    private static final int MAX_ARTWORK_BYTES = 5 * 1024 * 1024;

    static final class Payload {
        final long artworkId;
        final long version;
        final String contentType;
        final byte[] bytes;

        Payload(long artworkId, long version, String contentType, byte[] bytes) {
            this.artworkId = artworkId;
            this.version = version;
            this.contentType = contentType;
            this.bytes = bytes;
        }
    }

    private final PlaybackStateStore stateStore;
    private final AtomicReference<Payload> current = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "remote-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> encodingTask;

    RemoteArtworkCache(PlaybackStateStore stateStore) {
        this.stateStore = stateStore;
    }

    synchronized void update(long artworkId, Bitmap bitmap) {
        if (closed.get()) {
            return;
        }
        long requestGeneration = generation.incrementAndGet();
        if (encodingTask != null) {
            encodingTask.cancel(true);
            encodingTask = null;
        }
        if (artworkId <= 0L || bitmap == null) {
            current.set(null);
            stateStore.setArtworkAvailable(
                    artworkId,
                    false,
                    SystemClock.elapsedRealtime()
            );
            return;
        }

        try {
            encodingTask = executor.submit(() -> {
                byte[] encoded = encode(bitmap);
                if (closed.get() || requestGeneration != generation.get()) {
                    return;
                }
                if (encoded == null) {
                    current.set(null);
                    stateStore.setArtworkAvailable(
                            artworkId,
                            false,
                            SystemClock.elapsedRealtime()
                    );
                    return;
                }
                current.set(new Payload(artworkId, requestGeneration, "image/jpeg", encoded));
                stateStore.setArtworkAvailable(
                        artworkId,
                        true,
                        SystemClock.elapsedRealtime()
                );
            });
        } catch (RejectedExecutionException ignored) {
            current.set(null);
            stateStore.setArtworkAvailable(
                    artworkId,
                    false,
                    SystemClock.elapsedRealtime()
            );
        }
    }

    Payload get(long artworkId) {
        Payload payload = current.get();
        return payload != null && payload.artworkId == artworkId ? payload : null;
    }

    private static byte[] encode(Bitmap bitmap) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(128 * 1024)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                return null;
            }
            if (output.size() <= 0 || output.size() > MAX_ARTWORK_BYTES) {
                return null;
            }
            return output.toByteArray();
        } catch (RuntimeException | OutOfMemoryError exception) {
            return null;
        } catch (java.io.IOException impossible) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        generation.incrementAndGet();
        if (encodingTask != null) {
            encodingTask.cancel(true);
            encodingTask = null;
        }
        executor.shutdownNow();
        current.set(null);
    }
}
