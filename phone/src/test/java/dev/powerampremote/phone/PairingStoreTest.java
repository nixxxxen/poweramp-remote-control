package dev.powerampremote.phone;

import android.content.SharedPreferences;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class PairingStoreTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String TOKEN =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    public void successfulQrPairingSurvivesRestartAndMatchesDiscoveredReconnectTarget()
            throws Exception {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        new PairingStore(preferences).save(new PairingCredentials(
                SERVER_ID,
                "Poweramp Remote on player",
                "Living room player",
                TOKEN
        ));

        PairingCredentials restored = new PairingStore(preferences).load();

        assertNotNull(restored);
        assertEquals(SERVER_ID, restored.serverId);
        assertEquals("Poweramp Remote on player", restored.serviceName);
        assertEquals("Living room player", restored.deviceName);
        assertEquals(TOKEN, restored.token);
        assertTrue(restored.matches(new DiscoveredServer(
                SERVER_ID,
                "Poweramp Remote on player",
                InetAddress.getLoopbackAddress(),
                8765
        )));
        assertFalse(restored.matches(new DiscoveredServer(
                "AQEBAQEBAQEBAQEBAQEBAQ",
                "Different player",
                InetAddress.getLoopbackAddress(),
                8765
        )));
    }

    @Test
    public void manualBearerPairingPersistenceRemainsUnchanged() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        new PairingStore(preferences).save(new PairingCredentials(
                SERVER_ID,
                "LAN-discovered player",
                "LAN-discovered player",
                TOKEN
        ));

        PairingCredentials restored = new PairingStore(preferences).load();

        assertNotNull(restored);
        assertEquals(SERVER_ID, restored.serverId);
        assertEquals("LAN-discovered player", restored.serviceName);
        assertEquals(TOKEN, restored.token);
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defaultValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defaultValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defaultValues;
        }

        @Override
        public int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defaultValue;
        }

        @Override
        public long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defaultValue;
        }

        @Override
        public float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defaultValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defaultValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
        }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                updates.put(key, value == null ? null : new HashSet<>(value));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
