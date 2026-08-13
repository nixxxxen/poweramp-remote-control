package dev.r4remote.poweramp.phone;

/** Bounded exponential retry policy shared by discovery and WebSocket reconnect. */
final class ReconnectBackoff {
    private static final long MAX_DELAY_MILLISECONDS = 15_000L;

    private ReconnectBackoff() {
    }

    static long delayMilliseconds(int failedAttempts) {
        int exponent = Math.max(0, Math.min(failedAttempts, 4));
        return Math.min(1_000L << exponent, MAX_DELAY_MILLISECONDS);
    }
}
