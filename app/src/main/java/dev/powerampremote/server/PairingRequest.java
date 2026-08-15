package dev.powerampremote.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict request contract for the unauthenticated one-time pairing exchange. */
final class PairingRequest {
    private static final Pattern JSON_PATTERN = Pattern.compile(
            "\\s*\\{\\s*\\\"apiVersion\\\"\\s*:\\s*(\\d+)\\s*,\\s*"
                    + "\\\"serverId\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{22})\\\"\\s*,\\s*"
                    + "\\\"secret\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{43})\\\"\\s*}\\s*"
    );

    final int apiVersion;
    final String serverId;
    final String secret;

    PairingRequest(int apiVersion, String serverId, String secret) {
        this.apiVersion = apiVersion;
        this.serverId = serverId;
        this.secret = secret;
    }

    static PairingRequest parse(String json) {
        if (json == null) throw new IllegalArgumentException("missing request");
        Matcher matcher = JSON_PATTERN.matcher(json);
        if (!matcher.matches()) throw new IllegalArgumentException("invalid pairing request");
        int apiVersion;
        try {
            apiVersion = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid API version", exception);
        }
        String serverId = matcher.group(2);
        if (!ServerIdentity.isValid(serverId)) {
            throw new IllegalArgumentException("invalid server id");
        }
        return new PairingRequest(apiVersion, serverId, matcher.group(3));
    }
}
