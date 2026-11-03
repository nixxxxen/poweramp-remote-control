package dev.powerampremote.phone;

import android.content.Context;
import android.content.SharedPreferences;

/** Bounded Phone settings, independent of credentials, history, and Library preferences. */
final class ConnectionPreferences {
    private final SharedPreferences preferences;

    ConnectionPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "connection_settings", Context.MODE_PRIVATE
        );
    }

    ConnectionPolicy policy() {
        return ConnectionPolicy.fromStored(preferences.getString("policy", null));
    }

    boolean developerMode() { return preferences.getBoolean("developer_mode", false); }

    boolean savePolicy(ConnectionPolicy policy) {
        return preferences.edit().putString("policy", policy.name()).commit();
    }

    boolean saveDeveloperMode(boolean enabled) {
        return preferences.edit().putBoolean("developer_mode", enabled).commit();
    }
}
