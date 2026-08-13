package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RemoteWifiDirectContractTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String DOMAIN =
            "Poweramp Remote Server._poweramp-remote._tcp.local.";

    @Test
    public void acceptsOnlyTheKnownApiV1ServerAndValidPort() {
        Map<String, String> record = record();

        assertEquals(8765, RemoteWifiDirectContract.validatedPort(
                DOMAIN,
                record,
                SERVER_ID
        ));
        assertEquals(-1, RemoteWifiDirectContract.validatedPort(
                DOMAIN,
                record,
                "AQECAwQFBgcICQoLDA0ODw"
        ));
    }

    @Test
    public void rejectsWrongApiDomainAndMalformedPort() {
        Map<String, String> record = record();
        record.put(RemoteNsdContract.ATTRIBUTE_API_VERSION, "2");
        assertEquals(-1, RemoteWifiDirectContract.validatedPort(DOMAIN, record, SERVER_ID));

        record = record();
        record.put(RemoteNsdContract.ATTRIBUTE_PORT, "0");
        assertEquals(-1, RemoteWifiDirectContract.validatedPort(DOMAIN, record, SERVER_ID));

        assertEquals(-1, RemoteWifiDirectContract.validatedPort(
                "Other._other._tcp.local.",
                record(),
                SERVER_ID
        ));
    }

    private static Map<String, String> record() {
        Map<String, String> record = new LinkedHashMap<>();
        record.put(RemoteNsdContract.ATTRIBUTE_SERVER_ID, SERVER_ID);
        record.put(RemoteNsdContract.ATTRIBUTE_API_VERSION, "1");
        record.put(RemoteNsdContract.ATTRIBUTE_PORT, "8765");
        return record;
    }
}
