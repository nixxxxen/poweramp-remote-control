package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ByteLruCacheTest {
    @Test
    public void evictsLeastRecentlyUsedValuesToExactByteBudget() {
        ByteLruCache<String, Integer> cache = new ByteLruCache<>(7L, value -> value);

        cache.put("first", 3);
        cache.put("second", 3);
        assertEquals(Integer.valueOf(3), cache.get("first"));
        cache.put("third", 3);

        assertEquals(Integer.valueOf(3), cache.get("first"));
        assertNull(cache.get("second"));
        assertEquals(Integer.valueOf(3), cache.get("third"));
        assertEquals(6L, cache.byteCount());
        assertEquals(2, cache.size());
    }

    @Test
    public void rejectsOversizeValueWithoutEvictingUsefulEntries() {
        ByteLruCache<String, Integer> cache = new ByteLruCache<>(4L, value -> value);
        cache.put("kept", 4);

        cache.put("oversize", 5);
        cache.put("kept", 5);

        assertEquals(Integer.valueOf(4), cache.get("kept"));
        assertNull(cache.get("oversize"));
        assertEquals(4L, cache.byteCount());
    }
}
