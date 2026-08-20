package dev.powerampremote.server;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Validated request contract for the unauthenticated one-time pairing exchange. */
final class PairingRequest {
    final int apiVersion;
    final String serverId;
    final String secret;

    PairingRequest(int apiVersion, String serverId, String secret) {
        this.apiVersion = apiVersion;
        this.serverId = serverId;
        this.secret = secret;
    }

    static PairingRequest parse(String json) {
        if (json == null) throw invalidRequest();
        final JSONObject object;
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != '\0') {
                throw invalidRequest();
            }
            object = (JSONObject) parsed;
        } catch (JSONException | StackOverflowError exception) {
            // Never retain the parser exception as a cause: some Android implementations include
            // the source JSON (and therefore the one-time secret) in their diagnostic message.
            // A deeply nested unauthenticated body is likewise a request error, not a process
            // failure; catch only that parser-specific VM failure rather than broad Throwable.
            throw invalidRequest();
        }

        Object rawApiVersion = object.opt("apiVersion");
        Object rawServerId = object.opt("serverId");
        Object rawSecret = object.opt("secret");
        if (!(rawApiVersion instanceof Integer)
                || ((Integer) rawApiVersion) != PairingOffer.API_VERSION
                || !(rawServerId instanceof String)
                || !(rawSecret instanceof String)) {
            throw invalidRequest();
        }
        String serverId = (String) rawServerId;
        String secret = (String) rawSecret;
        if (!ServerIdentity.isValid(serverId) || !PairingSecretStore.isValidSecret(secret)) {
            throw invalidRequest();
        }
        return new PairingRequest((Integer) rawApiVersion, serverId, secret);
    }

    private static IllegalArgumentException invalidRequest() {
        return new IllegalArgumentException("invalid pairing request");
    }
}
