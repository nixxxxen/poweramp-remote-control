package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;

public final class PairingCredentialsTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    public void acceptsCanonicalServerIdentityAndToken() {
        assertTrue(PairingCredentials.isValidServerId(SERVER_ID));
        assertTrue(PairingCredentials.isValidToken(TOKEN));
        PairingCredentials credentials = new PairingCredentials(
                SERVER_ID,
                "Poweramp Remote Server",
                "HiBy R4",
                TOKEN
        );
        assertEquals(SERVER_ID, credentials.serverId);
        assertEquals("HiBy R4", credentials.deviceName);
        assertEquals(TOKEN, credentials.token);

        PairingCredentials migrated = new PairingCredentials(
                SERVER_ID,
                "Poweramp Remote Server",
                TOKEN
        );
        assertEquals("Poweramp Remote Server", migrated.deviceName);
    }

    @Test
    public void rejectsMalformedCredentials() {
        assertFalse(PairingCredentials.isValidServerId(SERVER_ID + "x"));
        assertFalse(PairingCredentials.isValidToken(TOKEN.substring(1)));
        assertFalse(PairingCredentials.isValidToken(TOKEN + "="));
    }

    @Test
    public void endpointBuildsIpv4ApiUrlWithoutPersistingAddress() throws Exception {
        DiscoveredServer server = new DiscoveredServer(SERVER_ID, "Poweramp Remote Server",
                InetAddress.getByName("192.168.1.20"), 8765);
        assertEquals("http://192.168.1.20:8765/api/v1/state",
                server.httpUrl("/api/v1/state").toString());
        assertEquals("192.168.1.20:8765", server.addressLabel());
    }
}
