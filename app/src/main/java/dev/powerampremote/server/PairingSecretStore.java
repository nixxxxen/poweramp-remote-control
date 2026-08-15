package dev.powerampremote.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

/** Thread-safe owner of the one active, short-lived, one-time pairing secret. */
final class PairingSecretStore {
    static final long SECRET_TTL_MILLISECONDS = 2L * 60L * 1_000L;
    private static final int SECRET_BYTES = 32;

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

    synchronized boolean consume(PairingRequest request) {
        PairingOffer offer = activeOffer;
        long now = clock.getAsLong();
        if (offer == null) return false;
        if (now >= offer.expiresAtMilliseconds) {
            activeOffer = null;
            return false;
        }
        if (request.apiVersion != PairingOffer.API_VERSION
                || !serverId.equals(request.serverId)
                || !constantTimeEquals(offer.secret, request.secret)) {
            return false;
        }
        activeOffer = null;
        return true;
    }

    synchronized boolean isActive(PairingOffer offer) {
        return offer != null
                && offer == activeOffer
                && clock.getAsLong() < offer.expiresAtMilliseconds;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] providedBytes = provided == null
                ? new byte[0]
                : provided.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
