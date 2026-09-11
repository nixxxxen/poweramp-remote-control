package dev.powerampremote.phone;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class LibrarySortPolicyTest {
    @Test
    public void parsesCapabilitiesAndFallsBackForOldOrUnknownServers() {
        LibrarySortCapabilities oldServer = LibrarySortCapabilities.parse(
                "{\"status\":\"available\"}"
        );
        assertFalse(oldServer.hasSelectableSort());
        assertTrue(oldServer.supports(LibrarySort.Criterion.DEFAULT));

        LibrarySortCapabilities current = LibrarySortCapabilities.parse(
                "{\"trackSorting\":{\"criteria\":[\"default\",\"title\","
                        + "\"date_added\",\"future\"],\"directions\":[\"asc\",\"desc\"]}}"
        );
        assertTrue(current.hasSelectableSort());
        assertTrue(current.supports(LibrarySort.Criterion.TITLE));
        assertTrue(current.supports(LibrarySort.Criterion.DATE_ADDED));
        assertFalse(current.supports(LibrarySort.Criterion.PLAY_COUNT));
        assertEquals(
                LibrarySort.POWERAMP,
                current.supportedOrDefault(new LibrarySort(
                        LibrarySort.Criterion.PLAY_COUNT,
                        LibrarySort.Direction.DESCENDING
                ))
        );

        expectInvalid(() -> LibrarySortCapabilities.parse(
                "{\"trackSorting\":{\"criteria\":[\"title\"],"
                        + "\"directions\":[\"asc\"]}}"
        ));
    }

    @Test
    public void persistenceUsesOnlyBoundedLogicalViewKeys() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        LibrarySortStore store = new LibrarySortStore(preferences);
        LibrarySort artist = new LibrarySort(
                LibrarySort.Criterion.ARTIST,
                LibrarySort.Direction.DESCENDING
        );
        LibrarySort playlist = new LibrarySort(
                LibrarySort.Criterion.PLAY_COUNT,
                LibrarySort.Direction.ASCENDING
        );

        assertTrue(store.save(LibrarySortView.ARTIST, artist));
        assertTrue(store.save(LibrarySortView.PLAYLIST, playlist));
        assertEquals(artist, new LibrarySortStore(preferences).load(LibrarySortView.ARTIST));
        assertEquals(playlist, store.load(LibrarySortView.PLAYLIST));
        assertEquals(LibrarySort.POWERAMP, store.load(LibrarySortView.ALBUM));
        assertEquals(4, preferences.getAll().size());
        assertTrue(preferences.contains("criterion_artist"));
        assertFalse(preferences.contains("criterion_artist_4259"));

        preferences.edit().putString("direction_artist", "invalid").commit();
        assertEquals(LibrarySort.POWERAMP, store.load(LibrarySortView.ARTIST));
    }

    @Test
    public void requestGenerationRejectsOldSortResponses() {
        LibrarySortRequestState state = new LibrarySortRequestState();
        LibrarySortRequestState.Stamp old = state.begin();
        LibrarySort title = LibrarySort.forCriterion(LibrarySort.Criterion.TITLE);
        assertTrue(state.change(title));
        assertFalse(state.accepts(old));
        LibrarySortRequestState.Stamp current = state.begin();
        assertTrue(state.accepts(current));
        assertFalse(state.change(title));
        assertTrue(state.accepts(current));
        assertTrue(state.change(new LibrarySort(
                LibrarySort.Criterion.TITLE,
                LibrarySort.Direction.DESCENDING
        )));
        assertFalse(state.accepts(current));
    }

    private static void expectInvalid(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(values);
        }
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
