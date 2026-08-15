package dev.powerampremote.phone;

/** Validated pairing work submitted to the service independently of an Activity binder. */
final class PairingRequest {
    enum Kind { QR, MANUAL_TOKEN }

    interface Target {
        void pairQr(PairingQrPayload payload);
        void pairManually(String token);
    }

    final Kind kind;
    final String value;

    private PairingRequest(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    static PairingRequest qr(String contents) {
        PairingQrPayload.parse(contents);
        return new PairingRequest(Kind.QR, contents);
    }

    static PairingRequest manualToken(String enteredToken) {
        String token = enteredToken == null ? "" : enteredToken.trim();
        if (!PairingCredentials.isValidToken(token)) {
            throw new IllegalArgumentException("invalid Bearer token");
        }
        return new PairingRequest(Kind.MANUAL_TOKEN, token);
    }

    void dispatchTo(Target target) {
        if (kind == Kind.QR) target.pairQr(PairingQrPayload.parse(value));
        else target.pairManually(value);
    }
}
