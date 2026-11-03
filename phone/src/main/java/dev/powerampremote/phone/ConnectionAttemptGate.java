package dev.powerampremote.phone;

/** One pending probe and one confirmed socket in the existing runtime. Tokens never revive. */
final class ConnectionAttemptGate {
    private long sequence;
    private long pending;
    private long confirmed;

    long begin() { pending = ++sequence; return pending; }
    boolean pending(long id) { return id != 0L && pending == id; }
    boolean confirmed(long id) { return id != 0L && confirmed == id; }
    boolean accepts(long id) { return pending(id) || confirmed(id); }

    boolean confirm(long id) {
        if (!pending(id)) return false;
        confirmed = id;
        pending = 0L;
        return true;
    }

    void cancelPending() { pending = 0L; }
    void loseConfirmed() { confirmed = 0L; }
    void cancelAll() { pending = 0L; confirmed = 0L; }
}
