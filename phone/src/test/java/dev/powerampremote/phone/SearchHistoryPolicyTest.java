package dev.powerampremote.phone;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public final class SearchHistoryPolicyTest {
    @Test
    public void newestEntryWinsAndStableNormalizationDeduplicatesDisplayVariants() {
        List<String> history = SearchHistoryPolicy.record(
                List.of("Old", "Sān‐Z", "Of Mice & Men"),
                "  san-z  "
        );
        assertEquals(List.of("san-z", "Old", "Of Mice & Men"), history);

        history = SearchHistoryPolicy.record(history, "of mice and men");
        assertEquals(List.of("of mice and men", "san-z", "Old"), history);
    }

    @Test
    public void individualRemovalAndMaximumSizeAreDeterministic() {
        List<String> history = Collections.emptyList();
        for (int index = 0; index < 25; index++) {
            history = SearchHistoryPolicy.record(history, "Query " + index);
        }
        assertEquals(SearchHistoryPolicy.MAXIMUM_ENTRIES, history.size());
        assertEquals("Query 24", history.get(0));
        assertEquals("Query 5", history.get(19));
        assertEquals(
                List.of("Query 24", "Query 22"),
                SearchHistoryPolicy.remove(
                        List.of("Query 24", "Query 23", "Query 22"), "query 23"
                )
        );
    }

    @Test
    public void storePersistsOnlyExplicitlyRecordedCompletedQueries() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        SearchHistoryStore store = new SearchHistoryStore(preferences);
        assertEquals(Collections.emptyList(), store.load());

        // Live debounce fragments are never passed to record by the UI.
        store.record("Moe Shop");
        store.record("Northlane — Obsidian");
        assertEquals(
                List.of("Northlane — Obsidian", "Moe Shop"),
                new SearchHistoryStore(preferences).load()
        );
        store.remove("moe shop");
        assertEquals(Collections.singletonList("Northlane — Obsidian"), store.load());
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(values); }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            return fallback;
        }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> updates = new HashMap<>();
                @Override public Editor putString(String key, String value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor putStringSet(String key, Set<String> value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor putInt(String key, int value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor putLong(String key, long value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor putFloat(String key, float value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor putBoolean(String key, boolean value) {
                    updates.put(key, value);
                    return this;
                }
                @Override public Editor remove(String key) {
                    updates.put(key, null);
                    return this;
                }
                @Override public Editor clear() {
                    values.clear();
                    updates.clear();
                    return this;
                }
                @Override public boolean commit() {
                    for (Map.Entry<String, Object> update : updates.entrySet()) {
                        if (update.getValue() == null) values.remove(update.getKey());
                        else values.put(update.getKey(), update.getValue());
                    }
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) { }
    }
}
