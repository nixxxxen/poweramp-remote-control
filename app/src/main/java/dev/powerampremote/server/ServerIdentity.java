package dev.powerampremote.server;

import java.security.SecureRandom;
import java.util.Base64;

/** Stable, non-secret identifier used to recognize one server across address changes. */
final class ServerIdentity {
    private static final int ID_BYTES = 16;
    private static final int ENCODED_LENGTH = 22;

    private ServerIdentity() {
    }

    static String generate(SecureRandom random) {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isValid(String serverId) {
        if (serverId == null || serverId.length() != ENCODED_LENGTH) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(serverId);
            return decoded.length == ID_BYTES
                    && serverId.equals(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
