package dev.r4remote.poweramp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class BearerAuth {
    private BearerAuth() {
    }

    static boolean isAuthorized(String authorizationHeader, String expectedToken) {
        if (authorizationHeader == null || expectedToken == null || expectedToken.isEmpty()) {
            return false;
        }
        int separator = authorizationHeader.indexOf(' ');
        if (separator <= 0
                || !"Bearer".equalsIgnoreCase(authorizationHeader.substring(0, separator))) {
            return false;
        }
        String token = authorizationHeader.substring(separator + 1).trim();
        if (token.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
