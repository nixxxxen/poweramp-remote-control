package dev.r4remote.poweramp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** DNS-SD contract shared with native LAN clients through mDNS TXT attributes. */
final class RemoteNsdContract {
    static final String SERVICE_TYPE = "_poweramp-remote._tcp.";
    static final String SERVICE_NAME = "Poweramp Remote Server";
    static final String ATTRIBUTE_SERVER_ID = "id";
    static final String ATTRIBUTE_API_VERSION = "api";
    static final String API_VERSION = "1";

    private RemoteNsdContract() {
    }

    static Map<String, String> attributes(String serverId) {
        if (!ServerIdentity.isValid(serverId)) {
            throw new IllegalArgumentException("invalid server id");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTRIBUTE_SERVER_ID, serverId);
        attributes.put(ATTRIBUTE_API_VERSION, API_VERSION);
        return Collections.unmodifiableMap(attributes);
    }
}
