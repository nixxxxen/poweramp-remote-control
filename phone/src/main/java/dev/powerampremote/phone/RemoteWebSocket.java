package dev.powerampremote.phone;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free RFC 6455 client for the server-push-only API v1 event stream. */
final class RemoteWebSocket implements AutoCloseable {
    enum FailureType { AUTHENTICATION, NETWORK, PROTOCOL }

    interface Listener {
        void onOpen();
        void onState(RemoteState state);
        void onFailure(FailureType type, Exception exception);
    }

    private static final String EVENTS_PATH = "/api/v1/events";
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MILLISECONDS = 10_000;
    private static final int HEARTBEAT_TIMEOUT_MILLISECONDS = 20_000;
    private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 24 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final DiscoveredServer server;
    private final String token;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object writeLock = new Object();

    private volatile boolean userClosed;
    private volatile Socket socket;
    private OutputStream output;

    RemoteWebSocket(DiscoveredServer server, String token, Listener listener) {
        this.server = server;
        this.token = token;
        this.listener = listener;
    }

    void start() {
        if (!started.compareAndSet(false, true)) return;
        Thread thread = new Thread(this::run, "remote-events");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        FailureType failureType = FailureType.NETWORK;
        Exception failure = null;
        try {
            connectAndRead();
            if (!userClosed) failure = new EOFException("WebSocket closed by server");
        } catch (AuthenticationException exception) {
            failureType = FailureType.AUTHENTICATION;
            failure = exception;
        } catch (ProtocolException | IllegalArgumentException exception) {
            failureType = FailureType.PROTOCOL;
            failure = exception;
        } catch (IOException exception) {
            failureType = FailureType.NETWORK;
            failure = exception;
        } finally {
            closeSocket();
        }
        if (!userClosed && failure != null) listener.onFailure(failureType, failure);
    }

    private void connectAndRead() throws IOException, AuthenticationException, ProtocolException {
        Socket connected = new Socket();
        socket = connected;
        connected.connect(new InetSocketAddress(server.address, server.port),
                CONNECT_TIMEOUT_MILLISECONDS);
        connected.setKeepAlive(true);
        connected.setTcpNoDelay(true);
        connected.setSoTimeout(HANDSHAKE_TIMEOUT_MILLISECONDS);
        BufferedInputStream input = new BufferedInputStream(connected.getInputStream());
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(connected.getOutputStream());
        output = bufferedOutput;
        performHandshake(input, bufferedOutput);
        connected.setSoTimeout(HEARTBEAT_TIMEOUT_MILLISECONDS);
        if (userClosed) return;
        listener.onOpen();
        readFrames(input);
    }

    private void performHandshake(InputStream input, OutputStream handshakeOutput)
            throws IOException, AuthenticationException, ProtocolException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + EVENTS_PATH + " HTTP/1.1\r\n"
                + "Host: " + server.hostHeader() + ':' + server.port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        handshakeOutput.write(request.getBytes(StandardCharsets.ISO_8859_1));
        handshakeOutput.flush();

        String statusLine = readLine(input, MAX_HEADER_LINE_BYTES);
        if (statusLine == null) throw new EOFException("Missing WebSocket response");
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/1.")) {
            throw new ProtocolException("Invalid HTTP status line");
        }
        int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException exception) {
            throw new ProtocolException("Invalid HTTP status");
        }
        if (status == 401) throw new AuthenticationException();
        if (status != 101) throw new ProtocolException("Unexpected HTTP status " + status);

        Map<String, String> headers = new HashMap<>();
        int headerBytes = statusLine.length() + 2;
        while (true) {
            String line = readLine(input, MAX_HEADER_LINE_BYTES);
            if (line == null) throw new EOFException("Incomplete WebSocket headers");
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) throw new ProtocolException("Headers too large");
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new ProtocolException("Invalid WebSocket header");
            headers.put(
                    line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim()
            );
        }
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))
                || !containsToken(headers.get("connection"), "upgrade")) {
            throw new ProtocolException("Missing upgrade headers");
        }
        String expected = expectedAccept(key);
        String actual = headers.get("sec-websocket-accept");
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("Invalid WebSocket accept");
        }
    }

    private void readFrames(InputStream input) throws IOException, ProtocolException {
        boolean awaitingPong = false;
        while (!userClosed) {
            Frame frame;
            try {
                frame = readFrame(input);
            } catch (SocketTimeoutException exception) {
                if (awaitingPong) throw new IOException("WebSocket heartbeat timed out", exception);
                sendMaskedFrame(0x09, new byte[0]);
                awaitingPong = true;
                continue;
            }
            switch (frame.opcode) {
                case 0x01:
                    listener.onState(RemoteStateParser.parse(decodeUtf8(frame.payload)));
                    break;
                case 0x08:
                    if (frame.payload.length == 1) throw new ProtocolException("Invalid close frame");
                    sendMaskedFrame(0x08, frame.payload);
                    return;
                case 0x09:
                    sendMaskedFrame(0x0A, frame.payload);
                    break;
                case 0x0A:
                    awaitingPong = false;
                    break;
                default:
                    throw new ProtocolException("Unsupported WebSocket opcode");
            }
        }
    }

    private static Frame readFrame(InputStream input) throws IOException, ProtocolException {
        int first = input.read();
        if (first < 0) throw new EOFException("WebSocket EOF");
        int second = input.read();
        if (second < 0) throw new EOFException("Incomplete WebSocket frame");
        boolean finalFrame = (first & 0x80) != 0;
        boolean reserved = (first & 0x70) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126L) {
            byte[] extended = readExactly(input, 2);
            length = (extended[0] & 0xFFL) << 8 | extended[1] & 0xFFL;
        } else if (length == 127L) {
            byte[] extended = readExactly(input, 8);
            if ((extended[0] & 0x80) != 0) throw new ProtocolException("Invalid frame length");
            length = 0L;
            for (byte value : extended) length = length << 8 | value & 0xFFL;
        }
        boolean control = opcode >= 0x08;
        if (!finalFrame || reserved || masked || length > MAX_PAYLOAD_BYTES
                || control && length > 125L) {
            throw new ProtocolException("Invalid WebSocket frame");
        }
        return new Frame(opcode, readExactly(input, (int) length));
    }

    private void sendMaskedFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (userClosed || output == null) throw new IOException("WebSocket is closed");
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            output.write(0x80 | opcode);
            if (payload.length <= 125) {
                output.write(0x80 | payload.length);
            } else if (payload.length <= 0xFFFF) {
                output.write(0x80 | 126);
                output.write(payload.length >> 8 & 0xFF);
                output.write(payload.length & 0xFF);
            } else {
                output.write(0x80 | 127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) (length >> shift & 0xFF));
                }
            }
            output.write(mask);
            for (int index = 0; index < payload.length; index++) {
                output.write(payload[index] ^ mask[index % 4]);
            }
            output.flush();
        }
    }

    static String expectedAccept(String key) throws ProtocolException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WEBSOCKET_GUID)
                    .getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ProtocolException("SHA-1 unavailable");
        }
    }

    private static String decodeUtf8(byte[] payload) throws ProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Invalid UTF-8 payload");
        }
    }

    private static boolean containsToken(String header, String expected) {
        if (header == null) return false;
        for (String token : header.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) return true;
        }
        return false;
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean carriageReturn = false;
        while (line.length() <= limit) {
            int value = input.read();
            if (value < 0) return line.length() == 0 && !carriageReturn ? null : line.toString();
            if (carriageReturn) {
                if (value == '\n') return line.toString();
                line.append('\r');
                carriageReturn = false;
            }
            if (value == '\r') carriageReturn = true;
            else if (value == '\n') return line.toString();
            else line.append((char) (value & 0xFF));
        }
        throw new IOException("Header line too long");
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("Incomplete WebSocket payload");
            offset += count;
        }
        return bytes;
    }

    @Override
    public void close() {
        userClosed = true;
        closeSocket();
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) { super(message); }
    }

    private static final class AuthenticationException extends Exception {
    }
}
