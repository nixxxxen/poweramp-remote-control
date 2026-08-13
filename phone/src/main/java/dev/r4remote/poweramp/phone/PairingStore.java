package dev.r4remote.poweramp.phone;

import android.content.Context;
import android.content.SharedPreferences;

/** Private local persistence for one verified server identity and Bearer token. */
final class PairingStore {
    private static final String PREFERENCES_NAME = "paired_server";
    private static final String KEY_SERVER_ID = "server_id";
    private static final String KEY_SERVICE_NAME = "service_name";
    private static final String KEY_TOKEN = "bearer_token";

    private final SharedPreferences preferences;

    PairingStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    PairingCredentials load() {
        String serverId = preferences.getString(KEY_SERVER_ID, null);
        String serviceName = preferences.getString(KEY_SERVICE_NAME, null);
        String token = preferences.getString(KEY_TOKEN, null);
        try {
            return new PairingCredentials(serverId, serviceName, token);
        } catch (IllegalArgumentException exception) {
            if (serverId != null || serviceName != null || token != null) {
                preferences.edit().clear().apply();
            }
            return null;
        }
    }

    void save(PairingCredentials credentials) {
        boolean saved = preferences.edit()
                .putString(KEY_SERVER_ID, credentials.serverId)
                .putString(KEY_SERVICE_NAME, credentials.serviceName)
                .putString(KEY_TOKEN, credentials.token)
                .commit();
        if (!saved) {
            throw new IllegalStateException("Unable to persist pairing credentials");
        }
    }

    void clear() {
        if (!preferences.edit().clear().commit()) {
            throw new IllegalStateException("Unable to clear pairing credentials");
        }
    }
}
