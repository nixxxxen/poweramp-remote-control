package dev.powerampremote.phone;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated address-free target and one-time secret scanned from the Server. */
final class PairingQrPayload {
    static final int API_VERSION = 1;
    private static final String SCHEME = "powerampremote";
    private static final String HOST = "pair";

    final int apiVersion;
    final String serverId;
    final String secret;
    final String deviceName;

    private PairingQrPayload(
            int apiVersion,
            String serverId,
            String secret,
            String deviceName
    ) {
        this.apiVersion = apiVersion;
        this.serverId = serverId;
        this.secret = secret;
        this.deviceName = deviceName;
    }

    static PairingQrPayload parse(String payload) {
        if (payload == null || payload.length() > 1_024) {
            throw new IllegalArgumentException("invalid QR payload");
        }
        URI uri;
        try {
            uri = new URI(payload);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid pairing URI", exception);
        }
        if (!SCHEME.equals(uri.getScheme())
                || !HOST.equals(uri.getHost())
                || !HOST.equals(uri.getRawAuthority())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("unexpected pairing URI");
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        if (query.size() != 4) {
            throw new IllegalArgumentException("unexpected pairing value");
        }
        int apiVersion;
        try {
            apiVersion = Integer.parseInt(required(query, "api"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid API version", exception);
        }
        String serverId = required(query, "id");
        String secret = required(query, "secret");
        String deviceName = required(query, "name").trim();
        if (apiVersion != API_VERSION
                || !PairingCredentials.isValidServerId(serverId)
                || !PairingCredentials.isValidSecret(secret)
                || deviceName.isEmpty()
                || deviceName.length() > 80) {
            throw new IllegalArgumentException("invalid pairing values");
        }
        return new PairingQrPayload(apiVersion, serverId, secret, deviceName);
    }

    String requestJson() {
        return "{\"apiVersion\":" + apiVersion
                + ",\"serverId\":\"" + serverId
                + "\",\"secret\":\"" + secret + "\"}";
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw new IllegalArgumentException("missing pairing query");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("invalid pairing query");
            String key = decode(part.substring(0, separator));
            String value = decode(part.substring(separator + 1));
            if (values.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate pairing value");
            }
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid query encoding", exception);
        }
    }
}
