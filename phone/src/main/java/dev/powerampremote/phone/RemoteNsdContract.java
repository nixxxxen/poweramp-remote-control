package dev.powerampremote.phone;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Public DNS-SD contract published by Poweramp Remote Server. */
final class RemoteNsdContract {
    static final String SERVICE_TYPE = "_poweramp-remote._tcp.";
    static final String WIFI_DIRECT_SERVICE_TYPE = "_poweramp-remote._tcp";
    static final String ATTRIBUTE_SERVER_ID = "id";
    static final String ATTRIBUTE_API_VERSION = "api";
    static final String ATTRIBUTE_PORT = "port";
    static final String API_VERSION = "1";

    private RemoteNsdContract() {
    }

    static boolean serviceTypeMatches(String serviceType) {
        return normalizeType(SERVICE_TYPE).equals(normalizeType(serviceType));
    }

    static String attribute(Map<String, byte[]> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        byte[] value = attributes.get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
