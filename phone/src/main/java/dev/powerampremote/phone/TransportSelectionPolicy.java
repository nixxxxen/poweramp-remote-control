package dev.powerampremote.phone;

/** Service-owned selection state. Network evidence is supplied by the controller, never inferred. */
final class TransportSelectionPolicy {
    // Existing Android negotiation (30s) + group check (5s) + API connect/upgrade/snapshot (25s).
    static final long SELECTION_TIMEOUT_MS = 60_000L;
    static final long ENDPOINT_COOLDOWN_MS = 60_000L;
    static final int PATH_FAILURE_LIMIT = 2;
    static final long PATH_FAILURE_WINDOW_MS = 15_000L;

    private ConnectionPolicy policy;
    private DiscoveredServer.Transport manual;
    private boolean switching;
    private boolean fallbackAllowed;
    private boolean preferenceChanged;
    private long recoveryUntilMs = -1L;

    TransportSelectionPolicy(ConnectionPolicy policy) { this.policy = policy; }

    ConnectionPolicy policy() { return policy; }
    boolean switching() { return switching; }
    boolean allowsDirect() { return policy.allowsDirect(); }

    void changePolicy(ConnectionPolicy next) {
        policy = next;
        reset();
        preferenceChanged = true;
    }

    void reset() {
        manual = null;
        switching = false;
        fallbackAllowed = false;
        preferenceChanged = false;
        recoveryUntilMs = -1L;
    }

    boolean beginManual(DiscoveredServer.Transport target) {
        if (switching || target == DiscoveredServer.Transport.WIFI_DIRECT && !allowsDirect()) {
            return false;
        }
        manual = target;
        switching = true;
        fallbackAllowed = false;
        recoveryUntilMs = -1L;
        return true;
    }

    DiscoveredServer.Transport preferred() {
        return manual != null ? manual : policy == ConnectionPolicy.DIRECT_PREFERRED
                ? DiscoveredServer.Transport.WIFI_DIRECT : DiscoveredServer.Transport.LAN;
    }

    void confirmed(DiscoveredServer.Transport transport) {
        if (manual == transport) switching = false;
        preferenceChanged = false;
        fallbackAllowed = false;
        recoveryUntilMs = -1L;
    }

    /** One window per disconnected cycle; callbacks cannot extend a window still in progress. */
    boolean beginRecovery(long nowMs) {
        if (switching || preferred() != DiscoveredServer.Transport.WIFI_DIRECT
                || !allowsDirect() || recoveryUntilMs >= 0L
                && (!fallbackAllowed || nowMs < recoveryUntilMs)) return false;
        recoveryUntilMs = nowMs + SELECTION_TIMEOUT_MS;
        fallbackAllowed = false;
        return true;
    }

    boolean prioritizesDirect(long nowMs) {
        return recoveryUntilMs >= 0L && nowMs < recoveryUntilMs && !fallbackAllowed
                && preferred() == DiscoveredServer.Transport.WIFI_DIRECT;
    }

    boolean shouldRetryDirectApi(boolean verifiedGroupUsable, long nowMs) {
        return verifiedGroupUsable && prioritizesDirect(nowMs);
    }

    void unavailable(DiscoveredServer.Transport transport) {
        if (manual == transport) {
            manual = null;
            switching = false;
        }
        // Losing a path is not proof that the preferred transport cannot recover. In particular,
        // an API failure can leave a verified P2P group usable throughout this recovery window.
        if (recoveryUntilMs < 0L || policy != ConnectionPolicy.DIRECT_PREFERRED) {
            fallbackAllowed = true;
        }
    }

    void allowFallback() { fallbackAllowed = true; preferenceChanged = false; }

    boolean maySelect(
            DiscoveredServer.Transport candidate,
            DiscoveredServer.Transport current,
            boolean healthy
    ) {
        if (candidate == DiscoveredServer.Transport.WIFI_DIRECT && !allowsDirect()) return false;
        if (switching) return candidate == manual;
        if (healthy) {
            if (candidate == current) return false;
            if (preferenceChanged && candidate == preferred()) return true;
            if (manual != null || policy == ConnectionPolicy.DIRECT_PREFERRED) return false;
            return candidate == DiscoveredServer.Transport.LAN;
        }
        return candidate == preferred() || fallbackAllowed;
    }

    static boolean exhausted(int failures, long elapsedMs) {
        return failures >= PATH_FAILURE_LIMIT || elapsedMs >= PATH_FAILURE_WINDOW_MS;
    }
}
