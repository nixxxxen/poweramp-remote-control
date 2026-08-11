package dev.r4remote.poweramp;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Base64;

final class ApiTokenStore {
    private static final String PREFERENCES_NAME = "remote_api";
    private static final String KEY_TOKEN = "bearer_token";
    private static final int TOKEN_BYTES = 32;

    private ApiTokenStore() {
    }

    static String loadOrCreate(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        String existing = preferences.getString(KEY_TOKEN, null);
        if (isValid(existing)) {
            return existing;
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (!preferences.edit().putString(KEY_TOKEN, token).commit()) {
            throw new IllegalStateException("Unable to persist API token");
        }
        return token;
    }

    private static boolean isValid(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return decoded.length == TOKEN_BYTES
                    && token.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
