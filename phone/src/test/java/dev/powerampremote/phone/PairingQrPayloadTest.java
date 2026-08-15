package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class PairingQrPayloadTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    public void parsesAddressFreeOneTimePairingTarget() {
        String encoded = "powerampremote://pair?api=1&id=" + SERVER_ID
                + "&secret=" + SECRET + "&name=HiBy+R4";
        PairingQrPayload payload = PairingQrPayload.parse(encoded);

        assertEquals(1, payload.apiVersion);
        assertEquals(SERVER_ID, payload.serverId);
        assertEquals(SECRET, payload.secret);
        assertEquals("HiBy R4", payload.deviceName);
        assertEquals("{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + SECRET + "\"}", payload.requestJson());
        assertFalse(encoded.contains("192.168."));
        assertFalse(encoded.contains(":8765"));
    }

    @Test
    public void rejectsWrongSchemeVersionAndNonCanonicalSecret() {
        reject("http://192.168.1.2/pair?api=1&id=" + SERVER_ID
                + "&secret=" + SECRET + "&name=Player");
        reject("powerampremote://pair?api=2&id=" + SERVER_ID
                + "&secret=" + SECRET + "&name=Player");
        reject("powerampremote://pair?api=1&id=" + SERVER_ID
                + "&secret=" + SECRET.substring(1) + "&name=Player");
        reject("powerampremote://pair:8765?api=1&id=" + SERVER_ID
                + "&secret=" + SECRET + "&name=Player");
        reject("powerampremote://pair?api=1&id=" + SERVER_ID
                + "&secret=" + SECRET + "&name=Player&ip=192.168.1.2");
    }

    private static void reject(String payload) {
        try {
            PairingQrPayload.parse(payload);
            fail("Expected invalid payload");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
