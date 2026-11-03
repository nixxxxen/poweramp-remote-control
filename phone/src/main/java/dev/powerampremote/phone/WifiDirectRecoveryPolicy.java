package dev.powerampremote.phone;

/** Pure retry state used by the P2P client so discovery recovers without looping first approval. */
final class WifiDirectRecoveryPolicy {
    private boolean connectedBefore;
    private int discoveryFailures;
    private boolean automaticNegotiationUsed;

    void onDiscoveryStarted() {
        discoveryFailures = 0;
    }

    long nextDiscoveryRetryDelayMilliseconds() {
        return ReconnectBackoff.delayMilliseconds(discoveryFailures++);
    }

    void onGroupConnected() {
        connectedBefore = true;
        automaticNegotiationUsed = false;
        discoveryFailures = 0;
    }

    boolean shouldAutomaticallyRetryConnection() {
        if (!connectedBefore || automaticNegotiationUsed) return false;
        automaticNegotiationUsed = true;
        return true;
    }
}
