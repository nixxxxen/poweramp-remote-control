package dev.powerampremote.server;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Short-lived QR payload. It deliberately contains neither an address nor a Bearer token. */
final class PairingOffer {
    static final int API_VERSION = 1;
    static final String URI_PREFIX = "powerampremote://pair";

    final String serverId;
    final String secret;
    final String deviceName;
    final long expiresAtMilliseconds;

    PairingOffer(
            String serverId,
            String secret,
            String deviceName,
            long expiresAtMilliseconds
    ) {
        this.serverId = serverId;
        this.secret = secret;
        this.deviceName = deviceName;
        this.expiresAtMilliseconds = expiresAtMilliseconds;
    }

    String qrPayload() {
        return URI_PREFIX
                + "?api=" + API_VERSION
                + "&id=" + urlEncode(serverId)
                + "&secret=" + urlEncode(secret)
                + "&name=" + urlEncode(deviceName);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
