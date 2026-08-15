package dev.powerampremote.phone;

import java.util.Map;

/** Strict successful response from POST /api/v1/pair. */
final class PairingExchangeResponse {
    final String serverId;
    final String deviceName;
    final String token;

    private PairingExchangeResponse(String serverId, String deviceName, String token) {
        this.serverId = serverId;
        this.deviceName = deviceName;
        this.token = token;
    }

    static PairingExchangeResponse parse(String json, String expectedServerId) {
        Map<String, Object> values = FlatJsonParser.parseObject(json);
        Long apiVersion = value(values, "apiVersion", Long.class);
        String serverId = value(values, "serverId", String.class);
        String deviceName = value(values, "deviceName", String.class);
        String token = value(values, "token", String.class);
        if (apiVersion == null || apiVersion != PairingQrPayload.API_VERSION
                || !expectedServerId.equals(serverId)
                || !PairingCredentials.isValidServerId(serverId)
                || deviceName == null || deviceName.trim().isEmpty()
                || deviceName.length() > 80
                || !PairingCredentials.isValidToken(token)) {
            throw new IllegalArgumentException("invalid pairing response");
        }
        return new PairingExchangeResponse(serverId, deviceName.trim(), token);
    }

    private static <T> T value(Map<String, Object> values, String key, Class<T> type) {
        Object value = values.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
