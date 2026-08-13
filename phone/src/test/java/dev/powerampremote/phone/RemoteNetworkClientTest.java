package dev.powerampremote.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RemoteNetworkClientTest {
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final String TOKEN =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String STATE_JSON = "{"
            + "\"apiVersion\":1,\"revision\":7,"
            + "\"powerampAvailable\":true,\"hasTrack\":false}";

    @Test
    public void restClientUsesBearerAndExactApiV1Routes() throws Exception {
        try (ServerSocket serverSocket = loopbackServer()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try {
                    HttpRequest state = acceptHttp(serverSocket);
                    assertEquals("GET", state.method);
                    assertEquals(RemoteApiClient.STATE_PATH, state.path);
                    assertEquals("Bearer " + TOKEN, state.headers.get("authorization"));
                    writeHttp(state.socket, 200, "OK",
                            "application/json; charset=utf-8",
                            STATE_JSON.getBytes(StandardCharsets.UTF_8));

                    HttpRequest control = acceptHttp(serverSocket);
                    assertEquals("POST", control.method);
                    assertEquals(RemoteApiClient.CONTROL_PATH, control.path);
                    assertEquals("Bearer " + TOKEN, control.headers.get("authorization"));
                    assertEquals(RemoteCommandJson.seek(37),
                            new String(control.body, StandardCharsets.UTF_8));
                    writeHttp(control.socket, 202, "Accepted",
                            "application/json; charset=utf-8",
                            "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));

                    HttpRequest artwork = acceptHttp(serverSocket);
                    assertEquals("GET", artwork.method);
                    assertEquals(RemoteApiClient.ARTWORK_PATH, artwork.path);
                    assertEquals("Bearer " + TOKEN, artwork.headers.get("authorization"));
                    writeHttp(artwork.socket, 200, "OK", "image/jpeg",
                            new byte[]{1, 2, 3, 4});
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });

            DiscoveredServer endpoint = endpoint(serverSocket);
            RemoteApiClient client = new RemoteApiClient();
            RemoteState state = client.getState(endpoint, TOKEN);
            assertEquals(7L, state.revision);
            assertTrue(state.powerampAvailable);
            client.sendControl(endpoint, TOKEN, RemoteCommandJson.seek(37));
            assertArrayEquals(new byte[]{1, 2, 3, 4},
                    client.getArtwork(endpoint, TOKEN, RemoteApiClient.ARTWORK_PATH));

            server.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    public void websocketPerformsBearerUpgradeAndDeliversStateEvent() throws Exception {
        try (ServerSocket serverSocket = loopbackServer()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(5_000);
                    HttpRequest request = readHttp(socket);
                    assertEquals("GET", request.method);
                    assertEquals("/api/v1/events", request.path);
                    assertEquals("Bearer " + TOKEN, request.headers.get("authorization"));
                    assertEquals("websocket", request.headers.get("upgrade"));
                    String key = request.headers.get("sec-websocket-key");
                    assertNotNull(key);
                    String response = "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: "
                            + RemoteWebSocket.expectedAccept(key) + "\r\n\r\n";
                    OutputStream output = socket.getOutputStream();
                    output.write(response.getBytes(StandardCharsets.ISO_8859_1));
                    writeServerTextFrame(output, STATE_JSON);
                    output.flush();
                    while (socket.getInputStream().read() >= 0) {
                        // The production client closes the socket after receiving the assertion event.
                    }
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });

            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<RemoteState> event = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            RemoteWebSocket webSocket = new RemoteWebSocket(
                    endpoint(serverSocket),
                    TOKEN,
                    new RemoteWebSocket.Listener() {
                        @Override
                        public void onOpen() {
                            opened.countDown();
                        }

                        @Override
                        public void onState(RemoteState state) {
                            event.set(state);
                            received.countDown();
                        }

                        @Override
                        public void onFailure(
                                RemoteWebSocket.FailureType type,
                                Exception exception
                        ) {
                            failure.set(exception);
                            received.countDown();
                        }
                    }
            );
            webSocket.start();
            assertTrue("WebSocket did not open", opened.await(5, TimeUnit.SECONDS));
            assertTrue("WebSocket state was not delivered", received.await(5, TimeUnit.SECONDS));
            webSocket.close();

            assertEquals(null, failure.get());
            assertNotNull(event.get());
            assertEquals(7L, event.get().revision);
            server.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        serverSocket.setSoTimeout(10_000);
        return serverSocket;
    }

    private static DiscoveredServer endpoint(ServerSocket serverSocket) {
        return new DiscoveredServer(
                SERVER_ID,
                "Poweramp Remote Server",
                serverSocket.getInetAddress(),
                serverSocket.getLocalPort()
        );
    }

    private static HttpRequest acceptHttp(ServerSocket serverSocket) throws IOException {
        Socket socket = serverSocket.accept();
        socket.setSoTimeout(5_000);
        return readHttp(socket);
    }

    private static HttpRequest readHttp(Socket socket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(input);
        if (requestLine == null) throw new EOFException("missing request line");
        String[] requestParts = requestLine.split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null) throw new EOFException("incomplete headers");
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            headers.put(
                    line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim()
            );
        }
        int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = readExactly(input, contentLength);
        return new HttpRequest(socket, requestParts[0], requestParts[1], headers, body);
    }

    private static void writeHttp(
            Socket socket,
            int status,
            String reason,
            String contentType,
            byte[] body
    ) throws IOException {
        try (Socket closeable = socket) {
            OutputStream output = closeable.getOutputStream();
            String headers = "HTTP/1.1 " + status + ' ' + reason + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
            output.write(body);
            output.flush();
        }
    }

    private static void writeServerTextFrame(OutputStream output, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        output.write(0x81);
        if (payload.length <= 125) {
            output.write(payload.length);
        } else {
            output.write(126);
            output.write(payload.length >> 8 & 0xFF);
            output.write(payload.length & 0xFF);
        }
        output.write(payload);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) return output.size() == 0 ? null
                    : output.toString(StandardCharsets.ISO_8859_1.name());
            if (previous == '\r' && value == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1),
                        StandardCharsets.ISO_8859_1);
            }
            output.write(value);
            previous = value;
        }
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("incomplete request body");
            offset += count;
        }
        return bytes;
    }

    private static final class HttpRequest {
        final Socket socket;
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(
                Socket socket,
                String method,
                String path,
                Map<String, String> headers,
                byte[] body
        ) {
            this.socket = socket;
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }
}
