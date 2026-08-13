package dev.r4remote.poweramp;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Persists the public NSD identity separately from the Bearer credential. */
final class ServerIdentityStore {
    private static final String PREFERENCES_NAME = "remote_api";
    private static final String KEY_SERVER_ID = "server_id";

    private ServerIdentityStore() {
    }

    static String loadOrCreate(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        String existing = preferences.getString(KEY_SERVER_ID, null);
        if (ServerIdentity.isValid(existing)) {
            return existing;
        }
        String serverId = ServerIdentity.generate(new SecureRandom());
        if (!preferences.edit().putString(KEY_SERVER_ID, serverId).commit()) {
            throw new IllegalStateException("Unable to persist server identity");
        }
        return serverId;
    }
}
