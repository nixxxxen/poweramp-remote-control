package dev.powerampremote.server;

import java.util.Locale;

final class TimeFormatter {
    private TimeFormatter() {
    }

    static String formatSeconds(int totalSeconds) {
        int safeSeconds = Math.max(totalSeconds, 0);
        int hours = safeSeconds / 3600;
        int minutes = (safeSeconds % 3600) / 60;
        int seconds = safeSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}

