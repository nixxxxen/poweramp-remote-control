package dev.powerampremote.phone;

/** The only persisted transport choices; a disabled transport can never be preferred. */
enum ConnectionPolicy {
    LAN_ONLY,
    LAN_PREFERRED,
    DIRECT_PREFERRED;

    boolean allowsDirect() { return this != LAN_ONLY; }

    static ConnectionPolicy fromSwitches(boolean allowDirect, boolean preferDirect) {
        return !allowDirect ? LAN_ONLY : preferDirect ? DIRECT_PREFERRED : LAN_PREFERRED;
    }

    static ConnectionPolicy fromStored(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return LAN_PREFERRED;
        }
    }
}
