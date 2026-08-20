package dev.powerampremote.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

/** Thread-safe owner of the one active, short-lived, one-time pairing secret. */
final class PairingSecretStore {
    enum ConsumeResult {
        ACCEPTED,
        NO_ACTIVE_OFFER,
        EXPIRED,
        API_VERSION_MISMATCH,
        SERVER_ID_MISMATCH,
        SECRET_MISMATCH
    }

    static final long SECRET_TTL_MILLISECONDS = 2L * 60L * 1_000L;
    private static final int SECRET_BYTES = 32;
    private static final int ENCODED_SECRET_LENGTH = 43;

    private final String serverId;
    private final String deviceName;
    private final SecureRandom random;
    private final LongSupplier clock;
    private PairingOffer activeOffer;

    PairingSecretStore(
            String serverId,
            String deviceName,
            SecureRandom random,
            LongSupplier clock
    ) {
        if (!ServerIdentity.isValid(serverId)) {
            throw new IllegalArgumentException("invalid server id");
        }
        if (deviceName == null || deviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("missing device name");
        }
        this.serverId = serverId;
        this.deviceName = deviceName.trim();
        this.random = random;
        this.clock = clock;
    }

    synchronized PairingOffer issue() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        activeOffer = new PairingOffer(
                serverId,
                secret,
                deviceName,
                clock.getAsLong() + SECRET_TTL_MILLISECONDS
        );
        return activeOffer;
    }

    synchronized ConsumeResult consume(PairingRequest request) {
        PairingOffer offer = activeOffer;
        long now = clock.getAsLong();
        if (offer == null) return ConsumeResult.NO_ACTIVE_OFFER;
        if (now >= offer.expiresAtMilliseconds) {
            activeOffer = null;
            return ConsumeResult.EXPIRED;
        }
        if (request.apiVersion != PairingOffer.API_VERSION) {
            return ConsumeResult.API_VERSION_MISMATCH;
        }
        if (!serverId.equals(request.serverId)) return ConsumeResult.SERVER_ID_MISMATCH;
        if (!constantTimeEquals(offer.secret, request.secret)) {
            return ConsumeResult.SECRET_MISMATCH;
        }
        activeOffer = null;
        return ConsumeResult.ACCEPTED;
    }

    synchronized boolean isActive(PairingOffer offer) {
        return offer != null
                && offer == activeOffer
                && clock.getAsLong() < offer.expiresAtMilliseconds;
    }

    static boolean isValidSecret(String secret) {
        if (secret == null || secret.length() != ENCODED_SECRET_LENGTH) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(secret);
            return decoded.length == SECRET_BYTES
                    && secret.equals(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] providedBytes = provided == null
                ? new byte[0]
                : provided.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
