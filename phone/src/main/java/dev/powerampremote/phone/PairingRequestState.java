package dev.powerampremote.phone;

/**
 * Service-owned handoff for pairing work.
 *
 * <p>A request can arrive while no Activity is bound and remains pending until the one connection
 * runtime is ready to consume it.</p>
 */
final class PairingRequestState {
    private PairingRequest pending;

    void submit(PairingRequest request) {
        if (request == null) throw new IllegalArgumentException("missing pairing request");
        pending = request;
    }

    boolean dispatchTo(PairingRequest.Target target) {
        if (pending == null || target == null) return false;
        PairingRequest request = pending;
        pending = null;
        request.dispatchTo(target);
        return true;
    }

    boolean hasPendingRequest() {
        return pending != null;
    }
}
