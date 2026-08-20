package dev.powerampremote.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class PairingRequestTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    public void parsesFieldsInAnyOrder() {
        PairingRequest request = PairingRequest.parse("{\"secret\":\"" + SECRET
                + "\",\"serverId\":\"" + SERVER_ID + "\",\"apiVersion\":1}");

        assertEquals(1, request.apiVersion);
        assertEquals(SERVER_ID, request.serverId);
        assertEquals(SECRET, request.secret);
    }

    @Test
    public void acceptsWhitespaceAndIgnoresUnknownFieldsOfAnyJsonType() {
        PairingRequest request = PairingRequest.parse("  {\n"
                + "  \"unknownObject\" : {\"nested\":true},\n"
                + "  \"serverId\" : \"" + SERVER_ID + "\",\n"
                + "  \"unknownArray\" : [1, null, false],\n"
                + "  \"apiVersion\" : 1,\n"
                + "  \"secret\" : \"" + SECRET + "\",\n"
                + "  \"unknownNull\" : null\n"
                + "  }  ");

        assertEquals(SERVER_ID, request.serverId);
        assertEquals(SECRET, request.secret);
    }

    @Test
    public void rejectsMalformedJsonAndNonObjectRoots() {
        assertInvalid(null);
        assertInvalid("{not-json}");
        assertInvalid("{\"apiVersion\":1");
        assertInvalid("[]");
        assertInvalid(validJson() + " trailing");
        assertInvalid("");
    }

    @Test
    public void rejectsMissingRequiredFields() {
        assertInvalid("{\"serverId\":\"" + SERVER_ID + "\",\"secret\":\""
                + SECRET + "\"}");
        assertInvalid("{\"apiVersion\":1,\"secret\":\"" + SECRET + "\"}");
        assertInvalid("{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID + "\"}");
    }

    @Test
    public void rejectsUnsupportedApiVersionAndWrongApiTypes() {
        assertInvalid(withApi("2"));
        assertInvalid(withApi("\"1\""));
        assertInvalid(withApi("1.0"));
        assertInvalid(withApi("true"));
        assertInvalid(withApi("null"));
    }

    @Test
    public void rejectsWrongServerIdTypesAndEncoding() {
        assertInvalid(withServerId("123"));
        assertInvalid(withServerId("null"));
        assertInvalid(withServerId("\"short\""));
        assertInvalid(withServerId("\"AAECAwQFBgcICQoLDA0OD!\""));
        assertInvalid(withServerId("\"AAECAwQFBgcICQoLDA0ODx\""));
    }

    @Test
    public void rejectsWrongSecretTypesAndEncoding() {
        assertInvalid(withSecret("123"));
        assertInvalid(withSecret("null"));
        assertInvalid(withSecret("\"short\""));
        assertInvalid(withSecret("\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh!\""));
        assertInvalid(withSecret("\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh9\""));
    }

    private static String validJson() {
        return "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + SECRET + "\"}";
    }

    private static String withApi(String rawValue) {
        return "{\"apiVersion\":" + rawValue + ",\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + SECRET + "\"}";
    }

    private static String withServerId(String rawValue) {
        return "{\"apiVersion\":1,\"serverId\":" + rawValue
                + ",\"secret\":\"" + SECRET + "\"}";
    }

    private static String withSecret(String rawValue) {
        return "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":" + rawValue + "}";
    }

    private static void assertInvalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> PairingRequest.parse(json));
    }
}
