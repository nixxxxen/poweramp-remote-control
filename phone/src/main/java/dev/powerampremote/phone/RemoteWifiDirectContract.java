package dev.powerampremote.phone;

import java.util.Locale;
import java.util.Map;

/** Validation for the public pre-association Wi-Fi Direct DNS-SD record. */
final class RemoteWifiDirectContract {
    private RemoteWifiDirectContract() {
    }

    static int validatedPort(
            String fullDomain,
            Map<String, String> record,
            String expectedServerId
    ) {
        if (!serviceDomainMatches(fullDomain)
                || record == null
                || !PairingCredentials.isValidServerId(expectedServerId)
                || !expectedServerId.equals(record.get(RemoteNsdContract.ATTRIBUTE_SERVER_ID))
                || !RemoteNsdContract.API_VERSION.equals(
                        record.get(RemoteNsdContract.ATTRIBUTE_API_VERSION)
                )) {
            return -1;
        }
        String value = record.get(RemoteNsdContract.ATTRIBUTE_PORT);
        if (value == null || value.isEmpty()) return -1;
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535 ? port : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    static boolean serviceDomainMatches(String fullDomain) {
        if (fullDomain == null) return false;
        String normalized = fullDomain.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String suffix = "." + RemoteNsdContract.WIFI_DIRECT_SERVICE_TYPE + ".local";
        return normalized.endsWith(suffix);
    }
}
