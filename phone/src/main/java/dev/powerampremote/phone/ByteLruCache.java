package dev.powerampremote.phone;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small synchronized access-order cache bounded by measured value bytes. */
final class ByteLruCache<K, V> {
    interface Weigher<V> {
        long bytes(V value);
    }

    private final long maximumBytes;
    private final Weigher<V> weigher;
    private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75f, true);
    private long currentBytes;

    ByteLruCache(long maximumBytes, Weigher<V> weigher) {
        if (maximumBytes <= 0L || weigher == null) {
            throw new IllegalArgumentException("Invalid cache budget");
        }
        this.maximumBytes = maximumBytes;
        this.weigher = weigher;
    }

    synchronized V get(K key) {
        return values.get(key);
    }

    synchronized void put(K key, V value) {
        long valueBytes = checkedBytes(value);
        if (valueBytes > maximumBytes) return;
        V previous = values.remove(key);
        if (previous != null) currentBytes -= checkedBytes(previous);
        values.put(key, value);
        currentBytes += valueBytes;
        trim();
    }

    synchronized V remove(K key) {
        V removed = values.remove(key);
        if (removed != null) currentBytes -= checkedBytes(removed);
        return removed;
    }

    synchronized void clear() {
        values.clear();
        currentBytes = 0L;
    }

    synchronized long byteCount() {
        return currentBytes;
    }

    synchronized int size() {
        return values.size();
    }

    private void trim() {
        Iterator<Map.Entry<K, V>> iterator = values.entrySet().iterator();
        while (currentBytes > maximumBytes && iterator.hasNext()) {
            Map.Entry<K, V> eldest = iterator.next();
            currentBytes -= checkedBytes(eldest.getValue());
            iterator.remove();
        }
    }

    private long checkedBytes(V value) {
        long bytes = weigher.bytes(value);
        if (bytes <= 0L) throw new IllegalArgumentException("Non-positive cache weight");
        return bytes;
    }
}
