package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Small bounded LRU that also coalesces concurrent analysis for the same key. */
final class AsyncResultCache<K, I, V> {
    interface Analyzer<I, V> {
        V analyze(I input);
    }

    interface Callback<K, V> {
        void onResult(K key, V value);
    }

    private final Executor analysisExecutor;
    private final Executor callbackExecutor;
    private final Analyzer<I, V> analyzer;
    private final LinkedHashMap<K, V> values;
    private final Map<K, List<Callback<K, V>>> inFlight = new HashMap<>();

    AsyncResultCache(
            int capacity,
            Executor analysisExecutor,
            Executor callbackExecutor,
            Analyzer<I, V> analyzer
    ) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.analysisExecutor = analysisExecutor;
        this.callbackExecutor = callbackExecutor;
        this.analyzer = analyzer;
        values = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    V getIfPresent(K key) {
        synchronized (this) {
            return values.get(key);
        }
    }

    boolean request(K key, I input, Callback<K, V> callback) {
        V cached;
        boolean startAnalysis = false;
        synchronized (this) {
            cached = values.get(key);
            if (cached == null) {
                List<Callback<K, V>> callbacks = inFlight.get(key);
                if (callbacks == null) {
                    callbacks = new ArrayList<>();
                    inFlight.put(key, callbacks);
                    startAnalysis = true;
                }
                callbacks.add(callback);
            }
        }
        if (cached != null) {
            dispatch(callback, key, cached);
            return true;
        }
        if (!startAnalysis) return true;
        try {
            analysisExecutor.execute(() -> analyze(key, input));
            return true;
        } catch (RuntimeException exception) {
            synchronized (this) {
                inFlight.remove(key);
            }
            return false;
        }
    }

    private void analyze(K key, I input) {
        V result;
        try {
            result = analyzer.analyze(input);
        } catch (RuntimeException exception) {
            result = null;
        }
        List<Callback<K, V>> callbacks;
        synchronized (this) {
            callbacks = inFlight.remove(key);
            if (result != null) values.put(key, result);
        }
        if (result == null || callbacks == null) return;
        for (Callback<K, V> callback : callbacks) dispatch(callback, key, result);
    }

    private void dispatch(Callback<K, V> callback, K key, V value) {
        try {
            callbackExecutor.execute(() -> callback.onResult(key, value));
        } catch (RuntimeException ignored) {
            // The owning lifecycle may already be gone; the cached value remains valid.
        }
    }

    int sizeForTesting() {
        synchronized (this) {
            return values.size();
        }
    }

    int inFlightCountForTesting() {
        synchronized (this) {
            return inFlight.size();
        }
    }
}
