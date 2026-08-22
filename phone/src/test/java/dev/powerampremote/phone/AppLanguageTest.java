package dev.powerampremote.phone;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AppLanguageTest {
    @Test
    public void systemDefaultRussianResolvesToRussian() {
        assertEquals(
                "ru",
                AppLanguage.SYSTEM.resolve(
                        Collections.singletonList(Locale.forLanguageTag("ru-RU"))
                ).getLanguage()
        );
    }

    @Test
    public void systemDefaultNonRussianResolvesToEnglish() {
        assertEquals(
                "en",
                AppLanguage.SYSTEM.resolve(Arrays.asList(
                        Locale.JAPANESE,
                        Locale.forLanguageTag("ru-RU")
                )).getLanguage()
        );
    }

    @Test
    public void explicitLanguagesIgnoreSystemLocale() {
        assertEquals("ru", AppLanguage.RUSSIAN.resolve(
                Collections.singletonList(Locale.ENGLISH)).getLanguage());
        assertEquals("en", AppLanguage.ENGLISH.resolve(
                Collections.singletonList(Locale.forLanguageTag("ru"))).getLanguage());
    }

    @Test
    public void persistedTagsAreStableAndUnknownValuesFallBackToSystem() {
        assertEquals("system", AppLanguage.SYSTEM.storageTag());
        assertEquals("ru", AppLanguage.RUSSIAN.storageTag());
        assertEquals("en", AppLanguage.ENGLISH.storageTag());
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageTag("unexpected"));

        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppLanguageStore store = new AppLanguageStore(preferences);
        assertEquals(AppLanguage.SYSTEM, store.load());
        assertTrue(store.save(AppLanguage.RUSSIAN));
        assertEquals(AppLanguage.RUSSIAN, new AppLanguageStore(preferences).load());
        assertEquals("ru", preferences.getString("language_tag", null));
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(values);
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defaultValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defaultValues) {
            return defaultValues;
        }

        @Override
        public int getInt(String key, int defaultValue) {
            return defaultValue;
        }

        @Override
        public long getLong(String key, long defaultValue) {
            return defaultValue;
        }

        @Override
        public float getFloat(String key, float defaultValue) {
            return defaultValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            return defaultValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> updates = new HashMap<>();

                @Override
                public Editor putString(String key, String value) {
                    updates.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> value) {
                    updates.put(key, value);
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
                    updates.put(key, null);
                    return this;
                }

                @Override
                public Editor clear() {
                    updates.clear();
                    values.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        if (entry.getValue() == null) values.remove(entry.getKey());
                        else values.put(entry.getKey(), entry.getValue());
                    }
                    return true;
                }

                @Override
                public void apply() {
                    commit();
                }
            };
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
    }
}
