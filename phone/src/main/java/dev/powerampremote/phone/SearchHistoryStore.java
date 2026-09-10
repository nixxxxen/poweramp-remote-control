package dev.powerampremote.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private Phone-only Search history, isolated from pairing state and library results. */
final class SearchHistoryStore {
    private static final String PREFERENCES_NAME = "search_history";
    private static final String KEY_ENTRIES = "entries";

    private final SharedPreferences preferences;

    SearchHistoryStore(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    SearchHistoryStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Missing preferences");
        this.preferences = preferences;
    }

    List<String> load() {
        String serialized = preferences.getString(KEY_ENTRIES, "[]");
        List<String> entries = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(serialized == null ? "[]" : serialized);
            for (int index = 0; index < values.length(); index++) {
                Object value = values.opt(index);
                if (value instanceof String) entries.add((String) value);
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(SearchHistoryPolicy.sanitize(entries));
    }

    boolean record(String query) {
        return save(SearchHistoryPolicy.record(load(), query));
    }

    boolean remove(String query) {
        return save(SearchHistoryPolicy.remove(load(), query));
    }

    private boolean save(List<String> entries) {
        JSONArray values = new JSONArray();
        for (String entry : entries) values.put(entry);
        return preferences.edit().putString(KEY_ENTRIES, values.toString()).commit();
    }
}
