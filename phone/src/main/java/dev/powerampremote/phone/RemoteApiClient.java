package dev.powerampremote.phone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Small Bearer-authenticated REST/artwork client for the fixed API v1 routes. */
final class RemoteApiClient {
    static final String STATE_PATH = "/api/v1/state";
    static final String CONTROL_PATH = "/api/v1/control";
    static final String ARTWORK_PATH = "/api/v1/artwork";
    static final String PAIRING_PATH = "/api/v1/pair";

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 10_000;
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private static final int MAX_ARTWORK_BYTES = 8 * 1024 * 1024;

    static final class HttpStatusException extends IOException {
        final int statusCode;

        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    PairingExchangeResponse exchangePairing(
            DiscoveredServer server,
            PairingQrPayload payload
    ) throws IOException {
        byte[] body = payload.requestJson().getBytes(StandardCharsets.UTF_8);
        Response response = request(
                server,
                null,
                "POST",
                PAIRING_PATH,
                body,
                MAX_JSON_BYTES
        );
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw new HttpStatusException(response.statusCode);
        }
        try {
            return PairingExchangeResponse.parse(
                    new String(response.body, StandardCharsets.UTF_8),
                    payload.serverId
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid pairing response", exception);
        }
    }

    RemoteState getState(DiscoveredServer server, String token) throws IOException {
        Response response = request(server, token, "GET", STATE_PATH, null, MAX_JSON_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw new HttpStatusException(response.statusCode);
        }
        try {
            return RemoteStateParser.parse(new String(response.body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid API state", exception);
        }
    }

    void sendControl(DiscoveredServer server, String token, String commandJson)
            throws IOException {
        byte[] body = commandJson.getBytes(StandardCharsets.UTF_8);
        Response response = request(server, token, "POST", CONTROL_PATH, body, MAX_JSON_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_ACCEPTED) {
            throw new HttpStatusException(response.statusCode);
        }
    }

    byte[] getArtwork(DiscoveredServer server, String token, String artworkPath)
            throws IOException {
        if (!ARTWORK_PATH.equals(artworkPath)) {
            throw new IOException("Unexpected artwork path");
        }
        Response response = request(server, token, "GET", artworkPath, null, MAX_ARTWORK_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw new HttpStatusException(response.statusCode);
        }
        if (response.contentType == null
                || !response.contentType.toLowerCase(Locale.ROOT).startsWith("image/jpeg")) {
            throw new IOException("Unexpected artwork content type");
        }
        return response.body;
    }

    private static Response request(
            DiscoveredServer server,
            String token,
            String method,
            String path,
            byte[] requestBody,
            int maxResponseBytes
    ) throws IOException {
        if (token != null && !PairingCredentials.isValidToken(token)) {
            throw new IOException("Invalid Bearer token");
        }
        HttpURLConnection connection = (HttpURLConnection) server.httpUrl(path).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("Connection", "close");
            if (requestBody != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(requestBody.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBody);
                }
            }
            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            byte[] responseBody = responseStream == null
                    ? new byte[0]
                    : readBounded(responseStream, maxResponseBytes);
            return new Response(statusCode, connection.getContentType(), responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream closeableInput = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = closeableInput.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    throw new IOException("Response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class Response {
        final int statusCode;
        final String contentType;
        final byte[] body;

        Response(int statusCode, String contentType, byte[] body) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
