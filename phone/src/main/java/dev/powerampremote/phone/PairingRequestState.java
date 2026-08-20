package dev.powerampremote.phone;

/**
 * Service-owned handoff for pairing work.
 *
 * <p>A request can arrive while no Activity is bound and remains pending until the one connection
 * runtime is ready to consume it.</p>
 */
final class PairingRequestState {
    enum SubmissionStatus {
        ACCEPTED,
        DUPLICATE_ACTIVE,
        REPLAY_SUCCESS,
        REPLAY_FAILURE
    }

    static final class Submission {
        final SubmissionStatus status;
        final String successfulDeviceName;
        final RemoteClientController.PairingError failure;

        private Submission(
                SubmissionStatus status,
                String successfulDeviceName,
                RemoteClientController.PairingError failure
        ) {
            this.status = status;
            this.successfulDeviceName = successfulDeviceName;
            this.failure = failure;
        }

        private static Submission accepted() {
            return new Submission(SubmissionStatus.ACCEPTED, null, null);
        }

        private static Submission duplicateActive() {
            return new Submission(SubmissionStatus.DUPLICATE_ACTIVE, null, null);
        }

        private static Submission replaySuccess(String deviceName) {
            return new Submission(SubmissionStatus.REPLAY_SUCCESS, deviceName, null);
        }

        private static Submission replayFailure(RemoteClientController.PairingError failure) {
            return new Submission(SubmissionStatus.REPLAY_FAILURE, null, failure);
        }
    }

    private PairingRequest pending;
    private PairingRequest active;
    private PairingRequest completedQr;
    private String completedDeviceName;
    private RemoteClientController.PairingError completedFailure;
    private boolean completedReplayable;

    Submission submitQr(String scannedContents, String deliveryId) {
        PairingRequest request = PairingRequest.qr(scannedContents, deliveryId);
        if (request.hasSameQrDelivery(pending) || request.hasSameQrDelivery(active)) {
            return Submission.duplicateActive();
        }
        if (request.hasSameQrDelivery(completedQr)) {
            if (pending != null || active != null || !completedReplayable) {
                return Submission.duplicateActive();
            }
            return completedFailure == null
                    ? Submission.replaySuccess(completedDeviceName)
                    : Submission.replayFailure(completedFailure);
        }
        pending = request;
        return Submission.accepted();
    }

    Submission submitManualToken(String enteredToken) {
        // Manual pairing is intentionally never deduplicated and begins a separate attempt.
        PairingRequest request = PairingRequest.manualToken(enteredToken);
        completedReplayable = false;
        pending = request;
        return Submission.accepted();
    }

    boolean dispatchTo(PairingRequest.Target target) {
        if (pending == null || target == null) return false;
        PairingRequest request = pending;
        pending = null;
        active = request;
        try {
            request.dispatchTo(target);
        } catch (RuntimeException exception) {
            active = null;
            throw exception;
        }
        return true;
    }

    void onPairingSucceeded(String deviceName) {
        if (active != null && active.kind == PairingRequest.Kind.QR) {
            completedQr = active;
            completedDeviceName = deviceName;
            completedFailure = null;
            completedReplayable = true;
        }
        active = null;
    }

    void onPairingFailed(RemoteClientController.PairingError failure) {
        if (active != null && active.kind == PairingRequest.Kind.QR) {
            completedQr = active;
            completedDeviceName = null;
            completedFailure = failure;
            completedReplayable = true;
        }
        active = null;
    }

    void reset() {
        pending = null;
        active = null;
        completedQr = null;
        completedDeviceName = null;
        completedFailure = null;
        completedReplayable = false;
    }

    boolean hasPendingRequest() {
        return pending != null;
    }
}
