package dev.powerampremote.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Map;

public final class ServerIdentityTest {
    @Test
    public void generatedIdentityIsStableFormatAndNotAnApiToken() {
        SecureRandom random = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) index;
                }
            }
        };

        String identity = ServerIdentity.generate(random);

        assertEquals("AAECAwQFBgcICQoLDA0ODw", identity);
        assertTrue(ServerIdentity.isValid(identity));
        assertEquals(22, identity.length());
        assertFalse(ServerIdentity.isValid(identity + "x"));
        assertFalse(ServerIdentity.isValid("not-an-identity"));
    }

    @Test
    public void nsdContractPublishesOnlyPublicIdentityAndApiVersion() {
        String identity = "AAECAwQFBgcICQoLDA0ODw";
        Map<String, String> attributes = RemoteNsdContract.attributes(identity);

        assertEquals("_poweramp-remote._tcp.", RemoteNsdContract.SERVICE_TYPE);
        assertEquals(2, attributes.size());
        assertEquals(identity, attributes.get(RemoteNsdContract.ATTRIBUTE_SERVER_ID));
        assertEquals("1", attributes.get(RemoteNsdContract.ATTRIBUTE_API_VERSION));
    }

    @Test
    public void wifiDirectContractAddsOnlyTheApiListenerPort() {
        String identity = "AAECAwQFBgcICQoLDA0ODw";
        Map<String, String> attributes = RemoteNsdContract.wifiDirectAttributes(identity, 8765);

        assertEquals("_poweramp-remote._tcp", RemoteNsdContract.WIFI_DIRECT_SERVICE_TYPE);
        assertEquals(3, attributes.size());
        assertEquals(identity, attributes.get(RemoteNsdContract.ATTRIBUTE_SERVER_ID));
        assertEquals("1", attributes.get(RemoteNsdContract.ATTRIBUTE_API_VERSION));
        assertEquals("8765", attributes.get(RemoteNsdContract.ATTRIBUTE_PORT));
        assertFalse(attributes.containsKey("token"));
    }
}
