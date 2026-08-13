package dev.powerampremote.phone;

import java.util.Locale;

final class TimeFormatter {
    private TimeFormatter() {
    }

    static String formatSeconds(int totalSeconds) {
        int safe = Math.max(totalSeconds, 0);
        int hours = safe / 3_600;
        int minutes = safe % 3_600 / 60;
        int seconds = safe % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
