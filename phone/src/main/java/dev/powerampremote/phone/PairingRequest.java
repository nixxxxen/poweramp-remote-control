package dev.powerampremote.phone;

import java.util.UUID;

/** Validated pairing work submitted to the service independently of an Activity binder. */
final class PairingRequest {
    enum Kind { QR, MANUAL_TOKEN }

    interface Target {
        void pairQr(PairingQrPayload payload);
        void pairManually(String token);
    }

    final Kind kind;
    final String value;
    private final PairingQrPayload qrPayload;
    private final String deliveryId;

    private PairingRequest(
            Kind kind,
            String value,
            PairingQrPayload qrPayload,
            String deliveryId
    ) {
        this.kind = kind;
        this.value = value;
        this.qrPayload = qrPayload;
        this.deliveryId = deliveryId;
    }

    static PairingRequest qr(String contents, String deliveryId) {
        PairingQrPayload payload = PairingQrPayload.parse(contents);
        return new PairingRequest(
                Kind.QR,
                contents,
                payload,
                validatedDeliveryId(deliveryId)
        );
    }

    static PairingRequest manualToken(String enteredToken) {
        String token = enteredToken == null ? "" : enteredToken.trim();
        if (!PairingCredentials.isValidToken(token)) {
            throw new IllegalArgumentException("invalid Bearer token");
        }
        return new PairingRequest(Kind.MANUAL_TOKEN, token, null, null);
    }

    void dispatchTo(Target target) {
        if (kind == Kind.QR) target.pairQr(qrPayload);
        else target.pairManually(value);
    }

    boolean hasSameQrDelivery(PairingRequest other) {
        return kind == Kind.QR
                && other != null
                && other.kind == Kind.QR
                && deliveryId.equals(other.deliveryId);
    }

    private static String validatedDeliveryId(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("invalid scanner delivery id");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical scanner delivery id");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid scanner delivery id", exception);
        }
    }
}
