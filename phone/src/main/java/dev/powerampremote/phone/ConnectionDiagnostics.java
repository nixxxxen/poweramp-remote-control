package dev.powerampremote.phone;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One safe event goes to both Logcat and the optional bounded process buffer. */
final class ConnectionDiagnostics {
    enum Reason {
        START, STOP, POLICY_CHANGED, MANUAL_SWITCH, SWITCH_TIMEOUT, DISCOVERY, NETWORK_CHANGED,
        LAN_LOST, ENDPOINT_FOUND, ENDPOINT_LOST, SOCKET_FAILURE, AUTH_REJECTED, P2P_STATE,
        SNAPSHOT, RETRY, FORGET, PAIRING, CANCELLED,
        PERMISSION_REQUIRED, LOCATION_DISABLED, WIFI_DISABLED, APPROVAL_REQUIRED,
        DIRECT_UNSUPPORTED, DIRECT_FAILED, PROTOCOL_FAILURE,
        RECOVERY_DIRECT_PRIORITY, RECOVERY_REUSE_GROUP, RECOVERY_DISCOVER_DIRECT,
        RECOVERY_LAN_FALLBACK, RECOVERY_SELECT_DIRECT, RECOVERY_SELECT_LAN
    }
    enum Stage { RUNTIME, DISCOVERY, CONNECT, UPGRADE, SNAPSHOT, RECOVERY, PERMISSION, APPROVAL }
    enum Result { STARTED, WAITING, SUCCEEDED, FAILED, CANCELLED, UNAVAILABLE }
    private static final ConnectionLogBuffer BUFFER = new ConnectionLogBuffer();

    private ConnectionDiagnostics() { }

    static void setEnabled(boolean enabled) { BUFFER.setEnabled(enabled); }
    static List<String> snapshot() { return BUFFER.snapshot(); }
    static void clear() { BUFFER.clear(); }

    static void record(
            Reason reason,
            DiscoveredServer.Transport transport,
            Stage stage,
            long elapsedMs,
            Result result
    ) {
        // Deliberately no overload accepting an endpoint, arbitrary text, JSON, or exception.
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date())
                + " reason=" + reason + " transport=" + (transport == null ? "NONE" : transport)
                + " stage=" + stage + " durationMs=" + Math.max(0L, elapsedMs)
                + " result=" + result;
        Log.i("PhoneConnection", line);
        BUFFER.add(line);
    }
}
