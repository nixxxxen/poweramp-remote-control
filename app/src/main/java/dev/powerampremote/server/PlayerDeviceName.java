package dev.powerampremote.server;

import android.os.Build;

import java.util.Locale;

/** Non-secret human-readable player-device label returned after pairing. */
final class PlayerDeviceName {
    private static final int MAX_LENGTH = 80;

    private PlayerDeviceName() {
    }

    static String current() {
        return format(Build.MANUFACTURER, Build.MODEL);
    }

    static String format(String manufacturer, String model) {
        String cleanManufacturer = clean(manufacturer);
        String cleanModel = clean(model);
        String result;
        if (cleanModel.isEmpty()) {
            result = cleanManufacturer.isEmpty() ? "Android player" : cleanManufacturer;
        } else if (cleanManufacturer.isEmpty()
                || cleanModel.toLowerCase(Locale.ROOT).startsWith(
                cleanManufacturer.toLowerCase(Locale.ROOT)
        )) {
            result = cleanModel;
        } else {
            result = cleanManufacturer + ' ' + cleanModel;
        }
        return result.length() <= MAX_LENGTH ? result : result.substring(0, MAX_LENGTH).trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
