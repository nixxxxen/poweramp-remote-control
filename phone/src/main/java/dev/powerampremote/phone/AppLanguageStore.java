package dev.powerampremote.phone;

import android.content.Context;
import android.content.SharedPreferences;

/** Private persistence isolated from pairing identity and Bearer credentials. */
final class AppLanguageStore {
    private static final String PREFERENCES_NAME = "app_language";
    private static final String KEY_LANGUAGE_TAG = "language_tag";

    private final SharedPreferences preferences;

    AppLanguageStore(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    AppLanguageStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("missing preferences");
        this.preferences = preferences;
    }

    AppLanguage load() {
        return AppLanguage.fromStorageTag(
                preferences.getString(KEY_LANGUAGE_TAG, AppLanguage.SYSTEM.storageTag())
        );
    }

    boolean save(AppLanguage language) {
        if (language == null) throw new IllegalArgumentException("missing language");
        return preferences.edit()
                .putString(KEY_LANGUAGE_TAG, language.storageTag())
                .commit();
    }
}
