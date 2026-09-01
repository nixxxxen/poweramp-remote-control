package dev.powerampremote.phone;

/** Pure grouping policy for the compact Main connection indicator. */
final class ConnectionIndicatorPolicy {
    enum PresentationState {
        LAN,
        WI_FI_DIRECT,
        CONNECTING,
        DISCONNECTED
    }

    static PresentationState resolve(RemoteClientController.Status status) {
        if (status == null) return PresentationState.DISCONNECTED;
        switch (status) {
            case CONNECTED:
                return PresentationState.LAN;
            case CONNECTED_DIRECT:
                return PresentationState.WI_FI_DIRECT;
            case SEARCHING:
            case PAIRING:
            case VERIFYING:
            case CONNECTING:
            case DIRECT_SEARCHING:
            case DIRECT_CONNECTING:
            case RETRYING:
                return PresentationState.CONNECTING;
            case DIRECT_PERMISSION_REQUIRED:
            case DIRECT_LOCATION_REQUIRED:
            case DIRECT_WIFI_REQUIRED:
            case DIRECT_UNSUPPORTED:
            case DIRECT_ACTION_REQUIRED:
            case AUTH_REQUIRED:
            case ERROR:
                return PresentationState.DISCONNECTED;
            default:
                throw new AssertionError("Unhandled connection status: " + status);
        }
    }

    static final class Tracker {
        private PresentationState renderedState;

        PresentationState update(RemoteClientController.Status status) {
            PresentationState nextState = resolve(status);
            if (renderedState == nextState) return null;
            renderedState = nextState;
            return nextState;
        }
    }

    private ConnectionIndicatorPolicy() {
    }
}
