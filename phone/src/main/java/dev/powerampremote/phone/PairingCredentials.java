package dev.powerampremote.phone;

import java.util.Base64;

/** Persisted association with one NSD identity and its Bearer credential. */
final class PairingCredentials {
    private static final int SERVER_ID_BYTES = 16;
    private static final int TOKEN_BYTES = 32;

    final String serverId;
    final String serviceName;
    final String token;

    PairingCredentials(String serverId, String serviceName, String token) {
        if (!isValidServerId(serverId)
                || serviceName == null
                || serviceName.trim().isEmpty()
                || !isValidToken(token)) {
            throw new IllegalArgumentException("invalid pairing credentials");
        }
        this.serverId = serverId;
        this.serviceName = serviceName;
        this.token = token;
    }

    static boolean isValidServerId(String value) {
        return isCanonicalBase64Url(value, SERVER_ID_BYTES, 22);
    }

    static boolean isValidToken(String value) {
        return isCanonicalBase64Url(value, TOKEN_BYTES, 43);
    }

    private static boolean isCanonicalBase64Url(String value, int byteCount, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == byteCount
                    && value.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
