package dev.powerampremote.phone;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-local palette cache whose completed entries contain only compact colors and keys. */
final class ArtworkPaletteRepository {
    private static final int CACHE_CAPACITY = 12;
    private static final int MAX_QUEUED_ANALYSES = 3;
    private static final ArtworkPaletteRepository INSTANCE = new ArtworkPaletteRepository();

    private final ThreadPoolExecutor analysisExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_ANALYSES),
            runnable -> {
                Thread thread = new Thread(runnable, "artwork-palette");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AsyncResultCache<String, Bitmap, ArtworkPalette> cache =
            new AsyncResultCache<>(
                    CACHE_CAPACITY,
                    analysisExecutor,
                    runnable -> mainHandler.post(runnable),
                    ArtworkPaletteBitmapAnalyzer::analyze
            );

    private ArtworkPaletteRepository() {
    }

    static ArtworkPaletteRepository get() {
        return INSTANCE;
    }

    ArtworkPalette getCached(String artworkKey) {
        return artworkKey == null ? null : cache.getIfPresent(artworkKey);
    }

    boolean request(
            String artworkKey,
            Bitmap artwork,
            AsyncResultCache.Callback<String, ArtworkPalette> callback
    ) {
        if (artworkKey == null || artwork == null) return false;
        return cache.request(artworkKey, artwork, callback);
    }
}
