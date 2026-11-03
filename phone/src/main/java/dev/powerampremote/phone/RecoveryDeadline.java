package dev.powerampremote.phone;

/** An earlier recovery deadline wins; repeated callbacks cannot postpone work. */
final class RecoveryDeadline {
    private long due = Long.MAX_VALUE;

    boolean schedule(long now, long delay) {
        long next = now + Math.max(0L, delay);
        if (next >= due) return false;
        due = next;
        return true;
    }

    void clear() { due = Long.MAX_VALUE; }
}
