package dev.powerampremote.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.BindException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small bounded HTTP/WebSocket server for the trusted-LAN prototype API. */
final class RemoteApiServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RemoteApiServer.class.getName());

    static final int PORT = 8765;
    static final String STATE_PATH = "/api/v1/state";
    static final String CONTROL_PATH = "/api/v1/control";
    static final String EVENTS_PATH = "/api/v1/events";
    static final String ARTWORK_PATH = RemoteStateJson.ARTWORK_PATH;
    static final String SESSION_PATH = "/api/v1/session";
    static final String PAIRING_PATH = "/api/v1/pair";

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int HTTP_TIMEOUT_MILLISECONDS = 10_000;
    private static final int MAX_REQUEST_LINE_BYTES = 4 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_WEBSOCKET_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_CONNECTION_THREADS = 16;
    private static final int MAX_WEBSOCKET_CLIENTS = 4;
    private static final Pattern SESSION_REQUEST_PATTERN = Pattern.compile(
            "\\s*\\{\\s*\\\"token\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,256})\\\"\\s*\\}\\s*"
    );
    private static final String WEB_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; "
                    + "img-src 'self' data:; connect-src 'self' ws: wss:; "
                    + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    interface CommandSubmitter {
        boolean submit(RemoteCommand command);
    }

    interface Listener {
        void onServerStatusChanged(Status status);
    }

    static final class Status {
        final boolean running;
        final int port;
        final int webSocketClients;
        final String error;
        final long sequence;

        Status(
                boolean running,
                int port,
                int webSocketClients,
                String error,
                long sequence
        ) {
            this.running = running;
            this.port = port;
            this.webSocketClients = webSocketClients;
            this.error = error;
            this.sequence = sequence;
        }
    }

    private static final class HttpException extends Exception {
        final int status;
        final String reason;
        final String errorCode;

        HttpException(int status, String reason, String errorCode) {
            this.status = status;
            this.reason = reason;
            this.errorCode = errorCode;
        }
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final String version;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(
                String method,
                String path,
                String version,
                Map<String, String> headers,
                byte[] body
        ) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }
    }

    private enum AuthorizationType {
        NONE,
        BEARER,
        SESSION
    }

    private static final class RequestAuthorization {
        final AuthorizationType type;
        final String sessionId;
        final long sessionExpiresAtMilliseconds;

        private RequestAuthorization(
                AuthorizationType type,
                String sessionId,
                long sessionExpiresAtMilliseconds
        ) {
            this.type = type;
            this.sessionId = sessionId;
            this.sessionExpiresAtMilliseconds = sessionExpiresAtMilliseconds;
        }

        static RequestAuthorization bearer() {
            return new RequestAuthorization(AuthorizationType.BEARER, null, Long.MAX_VALUE);
        }

        static RequestAuthorization session(BrowserSessionStore.Session session) {
            return new RequestAuthorization(
                    AuthorizationType.SESSION,
                    session.id,
                    session.expiresAtMilliseconds
            );
        }

        static RequestAuthorization none() {
            return new RequestAuthorization(AuthorizationType.NONE, null, 0L);
        }
    }

    private final int port;
    private final String token;
    private final PlaybackStateStore stateStore;
    private final RemoteArtworkCache artworkCache;
    private final CommandSubmitter commandSubmitter;
    private final Listener listener;
    private final LongSupplier monotonicClock;
    private final BrowserSessionStore browserSessions;
    private final PairingSecretStore pairingSecrets;
    private final String serverId;
    private final String playerDeviceName;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketConnection> webSockets = ConcurrentHashMap.newKeySet();
    private final Semaphore webSocketSlots = new Semaphore(MAX_WEBSOCKET_CLIENTS);
    private final ThreadPoolExecutor connectionExecutor = new ThreadPoolExecutor(
            0,
            MAX_CONNECTION_THREADS,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> daemonThread(runnable, "remote-api-client")
    );
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(
            runnable -> daemonThread(runnable, "remote-api-events")
    );
    private final ScheduledThreadPoolExecutor sessionExecutor = newSessionExecutor();
    private final AtomicReference<RemotePlaybackState> pendingEvent = new AtomicReference<>();
    private final AtomicBoolean eventDrainScheduled = new AtomicBoolean();
    private final AtomicLong statusSequence = new AtomicLong();
    private final PlaybackStateStore.Listener stateListener = this::enqueueState;

    private final Object lifecycleLock = new Object();
    private volatile boolean desiredRunning;
    private volatile boolean boundRunning;
    private volatile boolean closed;
    private volatile ServerSocket serverSocket;
    private volatile int lifecycleGeneration;

    RemoteApiServer(
            int port,
            String token,
            PlaybackStateStore stateStore,
            RemoteArtworkCache artworkCache,
            CommandSubmitter commandSubmitter,
            Listener listener,
            LongSupplier monotonicClock
    ) {
        this(
                port,
                token,
                stateStore,
                artworkCache,
                commandSubmitter,
                listener,
                monotonicClock,
                null,
                null,
                null
        );
    }

    RemoteApiServer(
            int port,
            String token,
            PlaybackStateStore stateStore,
            RemoteArtworkCache artworkCache,
            CommandSubmitter commandSubmitter,
            Listener listener,
            LongSupplier monotonicClock,
            PairingSecretStore pairingSecrets,
            String serverId,
            String playerDeviceName
    ) {
        this.port = port;
        this.token = token;
        this.stateStore = stateStore;
        this.artworkCache = artworkCache;
        this.commandSubmitter = commandSubmitter;
        this.listener = listener;
        this.monotonicClock = monotonicClock;
        this.browserSessions = new BrowserSessionStore(token, monotonicClock);
        this.pairingSecrets = pairingSecrets;
        this.serverId = serverId;
        this.playerDeviceName = playerDeviceName;
    }

    void start() {
        final int generation;
        synchronized (lifecycleLock) {
            if (closed || desiredRunning) {
                return;
            }
            desiredRunning = true;
            generation = ++lifecycleGeneration;
        }
        daemonThread(() -> runServer(generation), "remote-api-listener").start();
    }

    void stop() {
        synchronized (lifecycleLock) {
            desiredRunning = false;
            lifecycleGeneration++;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            boundRunning = false;
            stateStore.removeListener(stateListener);
            closeQuietly(socket);
            closeWebSockets();
            closeConnections();
            pendingEvent.set(null);
            notifyStatus(false, null);
        }
    }

    boolean isRunning() {
        return boundRunning;
    }

    private void runServer(int generation) {
        ServerSocket socket = null;
        String failure = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port), 16);
            synchronized (lifecycleLock) {
                if (!desiredRunning || generation != lifecycleGeneration || closed) {
                    closeQuietly(socket);
                    return;
                }
                serverSocket = socket;
                boundRunning = true;
                stateStore.addListener(stateListener);
                notifyStatus(true, null);
            }

            while (isCurrentGeneration(generation)) {
                Socket client;
                try {
                    client = socket.accept();
                } catch (SocketException exception) {
                    if (isCurrentGeneration(generation)) {
                        throw exception;
                    }
                    break;
                }
                configureAndSubmit(client, generation);
            }
        } catch (BindException exception) {
            if (isCurrentGeneration(generation)) {
                failure = "port_unavailable";
            }
        } catch (IOException | RuntimeException exception) {
            if (isCurrentGeneration(generation)) {
                failure = "server_error";
            }
        } finally {
            closeQuietly(socket);
            synchronized (lifecycleLock) {
                if (generation == lifecycleGeneration) {
                    desiredRunning = false;
                    boundRunning = false;
                    serverSocket = null;
                    stateStore.removeListener(stateListener);
                    closeWebSockets();
                    closeConnections();
                    notifyStatus(false, failure);
                }
            }
        }
    }

    private boolean isCurrentGeneration(int generation) {
        return desiredRunning && generation == lifecycleGeneration && !closed;
    }

    private void configureAndSubmit(Socket socket, int generation) {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(HTTP_TIMEOUT_MILLISECONDS);
            synchronized (lifecycleLock) {
                if (!desiredRunning || generation != lifecycleGeneration || closed) {
                    closeQuietly(socket);
                    return;
                }
                connections.add(socket);
            }
            connectionExecutor.execute(() -> handleConnection(socket, generation));
        } catch (IOException | RejectedExecutionException exception) {
            connections.remove(socket);
            closeQuietly(socket);
        }
    }

    private void handleConnection(Socket socket, int generation) {
        try (Socket closeableSocket = socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            HttpRequest request;
            try {
                request = readRequest(input);
            } catch (HttpException exception) {
                writeJsonError(output, exception.status, exception.reason, exception.errorCode);
                return;
            }
            if (!isCurrentGeneration(generation)) {
                writeJsonError(output, 503, "Service Unavailable", "server_stopping");
                return;
            }

            WebUiAssets.Asset asset = WebUiAssets.forPath(request.path);
            if (asset != null) {
                serveWebAsset(request, output, asset);
                return;
            }

            if (SESSION_PATH.equals(request.path) && "POST".equals(request.method)) {
                createBrowserSession(request, output);
                return;
            }

            if (PAIRING_PATH.equals(request.path)) {
                try {
                    exchangePairing(request, output);
                } catch (RuntimeException exception) {
                    // Android terminates the whole process for an uncaught exception on any
                    // application thread. Keep this request boundary diagnostic and do not log
                    // the body: it contains the one-time pairing secret.
                    LOGGER.log(
                            Level.SEVERE,
                            "Unexpected QR pairing request failure; request rejected",
                            exception
                    );
                    writeJsonError(
                            output,
                            500,
                            "Internal Server Error",
                            "pairing_internal_error"
                    );
                }
                return;
            }

            RequestAuthorization authorization = authorize(request);
            if (authorization.type == AuthorizationType.NONE) {
                writeUnauthorized(output);
                return;
            }

            if (authorization.type == AuthorizationType.SESSION
                    && requiresSameOrigin(request)
                    && !hasSameOrigin(request)) {
                writeJsonError(output, 403, "Forbidden", "same_origin_required");
                return;
            }

            if (EVENTS_PATH.equals(request.path)) {
                handleWebSocket(
                        request,
                        closeableSocket,
                        input,
                        output,
                        generation,
                        authorization
                );
                return;
            }
            routeHttp(request, output, generation, authorization);
        } catch (IOException ignored) {
            // The remote peer disconnected or the foreground service stopped the server.
        } finally {
            connections.remove(socket);
        }
    }

    private void routeHttp(
            HttpRequest request,
            OutputStream output,
            int generation,
            RequestAuthorization authorization
    )
            throws IOException {
        if (!isCurrentGeneration(generation)) {
            writeJsonError(output, 503, "Service Unavailable", "server_stopping");
            return;
        }
        if (STATE_PATH.equals(request.path)) {
            if (!"GET".equals(request.method)) {
                writeMethodNotAllowed(output, "GET");
                return;
            }
            String json = RemoteStateJson.toJson(
                    stateStore.snapshot(),
                    monotonicClock.getAsLong()
            );
            writeResponse(
                    output,
                    200,
                    "OK",
                    "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap()
            );
            return;
        }

        if (ARTWORK_PATH.equals(request.path)) {
            if (!"GET".equals(request.method)) {
                writeMethodNotAllowed(output, "GET");
                return;
            }
            RemotePlaybackState state = stateStore.snapshot();
            RemoteArtworkCache.Payload artwork = artworkCache.get(state.artworkId);
            if (!state.artworkAvailable || artwork == null) {
                writeJsonError(output, 404, "Not Found", "artwork_unavailable");
                return;
            }
            writeResponse(
                    output,
                    200,
                    "OK",
                    artwork.contentType,
                    artwork.bytes,
                    Collections.singletonMap(
                            "ETag",
                            "\"" + artwork.artworkId + '-' + artwork.version + "\""
                    )
            );
            return;
        }

        if (CONTROL_PATH.equals(request.path)) {
            if (!"POST".equals(request.method)) {
                writeMethodNotAllowed(output, "POST");
                return;
            }
            String contentType = request.headers.get("content-type");
            String mediaType = contentType == null
                    ? null
                    : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!"application/json".equals(mediaType)) {
                writeJsonError(output, 415, "Unsupported Media Type", "json_required");
                return;
            }
            RemoteCommand command;
            try {
                command = RemoteCommand.parse(new String(request.body, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                writeJsonError(output, 400, "Bad Request", "invalid_command");
                return;
            }
            if (!commandSubmitter.submit(command)) {
                writeJsonError(output, 503, "Service Unavailable", "client_inactive");
                return;
            }
            String response = "{\"accepted\":true,\"action\":\""
                    + command.action.wireName
                    + "\"}";
            writeResponse(
                    output,
                    202,
                    "Accepted",
                    "application/json; charset=utf-8",
                    response.getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap()
            );
            return;
        }

        if (SESSION_PATH.equals(request.path)) {
            if (!"DELETE".equals(request.method)) {
                writeMethodNotAllowed(output, "POST, DELETE");
                return;
            }
            if (authorization.type != AuthorizationType.SESSION || !hasSameOrigin(request)) {
                writeJsonError(output, 403, "Forbidden", "same_origin_required");
                return;
            }
            browserSessions.invalidateSession(authorization.sessionId);
            closeSessionWebSockets(authorization.sessionId);
            Map<String, String> headers = new HashMap<>();
            headers.put("Set-Cookie", BrowserSessionStore.clearCookieHeader());
            writeResponse(
                    output,
                    200,
                    "OK",
                    "application/json; charset=utf-8",
                    "{\"authenticated\":false}".getBytes(StandardCharsets.UTF_8),
                    headers
            );
            return;
        }

        writeJsonError(output, 404, "Not Found", "not_found");
    }

    private RequestAuthorization authorize(HttpRequest request) {
        String authorizationHeader = request.headers.get("authorization");
        if (authorizationHeader != null) {
            return BearerAuth.isAuthorized(authorizationHeader, token)
                    ? RequestAuthorization.bearer()
                    : RequestAuthorization.none();
        }
        BrowserSessionStore.Session session = browserSessions.authenticateCookie(
                request.headers.get("cookie")
        );
        return session == null
                ? RequestAuthorization.none()
                : RequestAuthorization.session(session);
    }

    private void exchangePairing(HttpRequest request, OutputStream output) throws IOException {
        if (!"POST".equals(request.method)) {
            writeMethodNotAllowed(output, "POST");
            return;
        }
        if (pairingSecrets == null || serverId == null || playerDeviceName == null) {
            writeJsonError(output, 503, "Service Unavailable", "pairing_unavailable");
            return;
        }
        String contentType = request.headers.get("content-type");
        String mediaType = contentType == null
                ? null
                : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/json".equals(mediaType)) {
            writeJsonError(output, 415, "Unsupported Media Type", "json_required");
            return;
        }
        PairingRequest pairingRequest;
        try {
            pairingRequest = PairingRequest.parse(
                    new String(request.body, StandardCharsets.UTF_8)
            );
        } catch (IllegalArgumentException exception) {
            // Expected parser failures are intentionally body-free: the body carries the secret.
            LOGGER.warning("Malformed QR pairing request rejected");
            writeJsonError(output, 400, "Bad Request", "invalid_pairing_request");
            return;
        }
        // Construct the immutable response before consuming the offer. If local identity or
        // credential serialization is unexpectedly broken, the one-time secret remains usable
        // and the guarded request boundary records the full cause.
        byte[] response = PairingResponse.toJson(serverId, playerDeviceName, token)
                .getBytes(StandardCharsets.UTF_8);
        PairingSecretStore.ConsumeResult consumeResult = pairingSecrets.consume(pairingRequest);
        if (consumeResult != PairingSecretStore.ConsumeResult.ACCEPTED) {
            LOGGER.warning("QR pairing request rejected: " + consumeResult.name());
            writeJsonError(output, 401, "Unauthorized", "pairing_rejected");
            return;
        }
        LOGGER.info("QR pairing request accepted; one-time offer consumed");
        writeResponse(
                output,
                200,
                "OK",
                "application/json; charset=utf-8",
                response,
                Collections.emptyMap()
        );
    }

    private static void serveWebAsset(
            HttpRequest request,
            OutputStream output,
            WebUiAssets.Asset asset
    ) throws IOException {
        if (!"GET".equals(request.method)) {
            writeMethodNotAllowed(output, "GET");
            return;
        }
        Map<String, String> headers = webSecurityHeaders();
        writeResponse(output, 200, "OK", asset.contentType, asset.body, headers);
    }

    private void createBrowserSession(HttpRequest request, OutputStream output)
            throws IOException {
        if (!hasSameOrigin(request)) {
            writeJsonError(output, 403, "Forbidden", "same_origin_required");
            return;
        }
        if (!"application/json".equals(mediaType(request))) {
            writeJsonError(output, 415, "Unsupported Media Type", "json_required");
            return;
        }
        Matcher matcher = SESSION_REQUEST_PATTERN.matcher(
                new String(request.body, StandardCharsets.UTF_8)
        );
        if (!matcher.matches()) {
            writeJsonError(output, 400, "Bad Request", "invalid_session_request");
            return;
        }
        BrowserSessionStore.Session session = browserSessions.createSession(matcher.group(1));
        if (session == null) {
            writeUnauthorized(output);
            return;
        }
        pruneUnauthorizedSessionWebSockets();

        Map<String, String> headers = new HashMap<>();
        headers.put("Set-Cookie", BrowserSessionStore.setCookieHeader(session));
        String response = "{\"authenticated\":true,\"expiresInSeconds\":"
                + BrowserSessionStore.SESSION_MAX_AGE_SECONDS
                + '}';
        writeResponse(
                output,
                200,
                "OK",
                "application/json; charset=utf-8",
                response.getBytes(StandardCharsets.UTF_8),
                headers
        );
    }

    private static String mediaType(HttpRequest request) {
        String contentType = request.headers.get("content-type");
        return contentType == null
                ? null
                : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean requiresSameOrigin(HttpRequest request) {
        return EVENTS_PATH.equals(request.path)
                || CONTROL_PATH.equals(request.path) && "POST".equals(request.method)
                || SESSION_PATH.equals(request.path) && "DELETE".equals(request.method);
    }

    private static boolean hasSameOrigin(HttpRequest request) {
        String origin = request.headers.get("origin");
        String host = request.headers.get("host");
        if (origin == null || host == null) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            String path = uri.getRawPath();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawAuthority() != null
                    && host.equalsIgnoreCase(uri.getRawAuthority())
                    && uri.getRawUserInfo() == null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static Map<String, String> webSecurityHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", WEB_CONTENT_SECURITY_POLICY);
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("X-Frame-Options", "DENY");
        return headers;
    }

    private void closeSessionWebSockets(String sessionId) {
        if (sessionId == null) {
            return;
        }
        for (WebSocketConnection connection : webSockets) {
            if (sessionId.equals(connection.sessionId)) {
                connection.closeUnauthorized();
            }
        }
    }

    private void pruneUnauthorizedSessionWebSockets() {
        for (WebSocketConnection connection : webSockets) {
            if (connection.sessionId != null
                    && browserSessions.authenticateSessionId(connection.sessionId) == null) {
                connection.closeUnauthorized();
            }
        }
    }

    private void handleWebSocket(
            HttpRequest request,
            Socket socket,
            InputStream input,
            OutputStream output,
            int generation,
            RequestAuthorization authorization
    ) throws IOException {
        if (!"GET".equals(request.method)) {
            writeMethodNotAllowed(output, "GET");
            return;
        }
        if (!"HTTP/1.1".equals(request.version)) {
            writeJsonError(output, 400, "Bad Request", "websocket_requires_http_1_1");
            return;
        }
        String upgrade = request.headers.get("upgrade");
        String connection = request.headers.get("connection");
        String version = request.headers.get("sec-websocket-version");
        String key = request.headers.get("sec-websocket-key");
        if (!"websocket".equalsIgnoreCase(upgrade)
                || !containsHeaderToken(connection, "upgrade")
                || !"13".equals(version)
                || !validWebSocketKey(key)) {
            writeJsonError(output, 400, "Bad Request", "invalid_websocket_upgrade");
            return;
        }

        if (!webSocketSlots.tryAcquire()) {
            writeJsonError(output, 503, "Service Unavailable", "too_many_websockets");
            return;
        }
        WebSocketConnection connectionSocket = new WebSocketConnection(
                socket,
                input,
                output,
                authorization.sessionId
        );
        try {
            boolean active;
            synchronized (lifecycleLock) {
                active = desiredRunning && generation == lifecycleGeneration && !closed;
            }
            if (!active) {
                writeJsonError(output, 503, "Service Unavailable", "server_stopping");
                return;
            }
            if (authorization.type == AuthorizationType.SESSION
                    && browserSessions.authenticateSessionId(authorization.sessionId) == null) {
                writeUnauthorized(output);
                return;
            }

            String accept = webSocketAccept(key);
            String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n";
            output.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            socket.setSoTimeout(0);
            if (authorization.type == AuthorizationType.SESSION) {
                connectionSocket.scheduleSessionExpiry(
                        sessionExecutor,
                        browserSessions,
                        monotonicClock,
                        authorization.sessionExpiresAtMilliseconds
                );
            }

            RemotePlaybackState initialState = stateStore.snapshot();
            connectionSocket.sendState(initialState.revision, RemoteStateJson.toJson(
                    initialState,
                    monotonicClock.getAsLong()
            ));

            synchronized (lifecycleLock) {
                if (!desiredRunning || generation != lifecycleGeneration || closed) {
                    return;
                }
                webSockets.add(connectionSocket);
                notifyStatus(true, null);
            }
            RemotePlaybackState catchUpState = stateStore.snapshot();
            connectionSocket.sendState(catchUpState.revision, RemoteStateJson.toJson(
                    catchUpState,
                    monotonicClock.getAsLong()
            ));
            connectionSocket.readUntilClosed();
        } finally {
            webSockets.remove(connectionSocket);
            connectionSocket.closeSocket();
            webSocketSlots.release();
            synchronized (lifecycleLock) {
                notifyStatus(boundRunning, null);
            }
        }
    }

    private void enqueueState(RemotePlaybackState state) {
        pendingEvent.set(state);
        if (eventDrainScheduled.compareAndSet(false, true)) {
            try {
                eventExecutor.execute(this::drainEvents);
            } catch (RejectedExecutionException ignored) {
                eventDrainScheduled.set(false);
            }
        }
    }

    private void drainEvents() {
        while (true) {
            RemotePlaybackState state = pendingEvent.getAndSet(null);
            if (state != null && boundRunning) {
                String json = RemoteStateJson.toJson(state, monotonicClock.getAsLong());
                for (WebSocketConnection connection : webSockets) {
                    if (connection.sessionId != null
                            && browserSessions.authenticateSessionId(connection.sessionId) == null) {
                        connection.closeUnauthorized();
                        continue;
                    }
                    try {
                        connection.sendState(state.revision, json);
                    } catch (IOException exception) {
                        connection.closeSocket();
                    }
                }
            }
            eventDrainScheduled.set(false);
            if (pendingEvent.get() == null
                    || !eventDrainScheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void closeWebSockets() {
        for (WebSocketConnection connection : webSockets) {
            // Lifecycle shutdown must never wait for a slow peer while holding the UI thread.
            connection.closeSocket();
        }
        webSockets.clear();
    }

    private void closeConnections() {
        for (Socket socket : connections) {
            closeQuietly(socket);
        }
        connections.clear();
    }

    private void notifyStatus(boolean running, String error) {
        listener.onServerStatusChanged(new Status(
                running,
                port,
                webSockets.size(),
                error,
                statusSequence.incrementAndGet()
        ));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        stop();
        browserSessions.close();
        connectionExecutor.shutdownNow();
        eventExecutor.shutdownNow();
        sessionExecutor.shutdownNow();
    }

    private static HttpRequest readRequest(BufferedInputStream input)
            throws IOException, HttpException {
        String requestLine = readLine(input, MAX_REQUEST_LINE_BYTES);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new HttpException(400, "Bad Request", "empty_request");
        }
        String[] parts = requestLine.trim().split("\\s+");
        if (parts.length != 3
                || (!"HTTP/1.0".equals(parts[2]) && !"HTTP/1.1".equals(parts[2]))) {
            throw new HttpException(400, "Bad Request", "invalid_request_line");
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        if (!target.startsWith("/")) {
            throw new HttpException(400, "Bad Request", "invalid_target");
        }
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;

        Map<String, String> headers = new HashMap<>();
        int headerBytes = requestLine.length() + 2;
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES);
            if (line == null) {
                throw new HttpException(400, "Bad Request", "incomplete_headers");
            }
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new HttpException(431, "Request Header Fields Too Large", "headers_too_large");
            }
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new HttpException(400, "Bad Request", "invalid_header");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (name.isEmpty() || headers.containsKey(name)) {
                throw new HttpException(400, "Bad Request", "duplicate_header");
            }
            headers.put(name, value);
        }
        if ("HTTP/1.1".equals(parts[2]) && !headers.containsKey("host")) {
            throw new HttpException(400, "Bad Request", "host_required");
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpException(400, "Bad Request", "transfer_encoding_not_supported");
        }

        int contentLength = 0;
        String contentLengthText = headers.get("content-length");
        if (contentLengthText != null) {
            try {
                contentLength = Integer.parseInt(contentLengthText);
            } catch (NumberFormatException exception) {
                throw new HttpException(400, "Bad Request", "invalid_content_length");
            }
            if (contentLength < 0) {
                throw new HttpException(400, "Bad Request", "invalid_content_length");
            }
            if (contentLength > MAX_BODY_BYTES) {
                throw new HttpException(413, "Payload Too Large", "body_too_large");
            }
        } else if ("POST".equals(method)) {
            throw new HttpException(411, "Length Required", "content_length_required");
        }
        byte[] body = readExactly(input, contentLength);
        return new HttpRequest(method, path, parts[2], headers, body);
    }

    private static String readLine(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= maximumBytes) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0
                        ? null
                        : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(value);
        }
        throw new IOException("line too long");
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException("incomplete request body");
            }
            offset += read;
        }
        return bytes;
    }

    private static void writeMethodNotAllowed(OutputStream output, String allowed)
            throws IOException {
        writeResponse(
                output,
                405,
                "Method Not Allowed",
                "application/json; charset=utf-8",
                jsonError("method_not_allowed"),
                Collections.singletonMap("Allow", allowed)
        );
    }

    private static void writeJsonError(
            OutputStream output,
            int status,
            String reason,
            String code
    ) throws IOException {
        writeResponse(
                output,
                status,
                reason,
                "application/json; charset=utf-8",
                jsonError(code),
                Collections.emptyMap()
        );
    }

    private static void writeUnauthorized(OutputStream output) throws IOException {
        writeResponse(
                output,
                401,
                "Unauthorized",
                "application/json; charset=utf-8",
                jsonError("unauthorized"),
                Collections.singletonMap("WWW-Authenticate", "Bearer")
        );
    }

    private static byte[] jsonError(String code) {
        return ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeResponse(
            OutputStream output,
            int status,
            String reason,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders
    ) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Cache-Control: no-store\r\n");
        headers.append("X-Content-Type-Options: nosniff\r\n");
        headers.append("Connection: close\r\n");
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static boolean containsHeaderToken(String header, String expected) {
        if (header == null) {
            return false;
        }
        for (String value : header.split(",")) {
            if (expected.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean validWebSocketKey(String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String webSocketAccept(String key) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                    (key + WEBSOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1)
            );
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-1 is unavailable", impossible);
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static ScheduledThreadPoolExecutor newSessionExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                runnable -> daemonThread(runnable, "remote-api-sessions")
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static final class WebSocketConnection {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final String sessionId;
        private final Object sessionTaskLock = new Object();
        private volatile boolean closed;
        private volatile ScheduledFuture<?> sessionExpiryTask;
        private long lastSentRevision = -1L;

        WebSocketConnection(
                Socket socket,
                InputStream input,
                OutputStream output,
                String sessionId
        ) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.sessionId = sessionId;
        }

        void scheduleSessionExpiry(
                ScheduledExecutorService executor,
                BrowserSessionStore sessions,
                LongSupplier clock,
                long expiresAtMilliseconds
        ) {
            if (closed) {
                return;
            }
            long delay = Math.max(1L, expiresAtMilliseconds - clock.getAsLong());
            ScheduledFuture<?> scheduled;
            try {
                scheduled = executor.schedule(() -> {
                    if (sessions.authenticateSessionId(sessionId) == null) {
                        closeUnauthorized();
                    } else {
                        scheduleSessionExpiry(
                                executor,
                                sessions,
                                clock,
                                expiresAtMilliseconds
                        );
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // The application is shutting down.
                return;
            }
            ScheduledFuture<?> previous;
            synchronized (sessionTaskLock) {
                if (closed) {
                    scheduled.cancel(false);
                    return;
                }
                previous = sessionExpiryTask;
                sessionExpiryTask = scheduled;
            }
            if (previous != null && previous != scheduled) {
                previous.cancel(false);
            }
        }

        void readUntilClosed() throws IOException {
            while (!closed) {
                int first = input.read();
                if (first < 0) {
                    return;
                }
                int second = input.read();
                if (second < 0) {
                    throw new EOFException("incomplete websocket frame");
                }
                boolean finalFrame = (first & 0x80) != 0;
                boolean hasReservedBits = (first & 0x70) != 0;
                int opcode = first & 0x0F;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126L) {
                    byte[] extended = readExactly(input, 2);
                    length = ((extended[0] & 0xFFL) << 8) | (extended[1] & 0xFFL);
                } else if (length == 127L) {
                    byte[] extended = readExactly(input, 8);
                    if ((extended[0] & 0x80) != 0) {
                        closeProtocolError();
                        return;
                    }
                    length = 0L;
                    for (byte value : extended) {
                        length = (length << 8) | (value & 0xFFL);
                    }
                }
                boolean controlFrame = opcode >= 0x08;
                if (!masked || hasReservedBits
                        || length > MAX_WEBSOCKET_PAYLOAD_BYTES
                        || controlFrame && (!finalFrame || length > 125L)) {
                    closeProtocolError();
                    return;
                }
                byte[] mask = readExactly(input, 4);
                byte[] payload = readExactly(input, (int) length);
                for (int index = 0; index < payload.length; index++) {
                    payload[index] = (byte) (payload[index] ^ mask[index % 4]);
                }

                switch (opcode) {
                    case 0x08:
                        if (payload.length == 1) {
                            closeProtocolError();
                            return;
                        }
                        sendClose(1000);
                        closed = true;
                        return;
                    case 0x09:
                        sendFrame(0x0A, payload);
                        break;
                    case 0x0A:
                        break;
                    case 0x00:
                    case 0x01:
                    case 0x02:
                        // v1 is server-push only. Commands stay on authenticated POST /control.
                        sendClose(1008);
                        return;
                    default:
                        closeProtocolError();
                        return;
                }
            }
        }

        synchronized void sendState(long revision, String text) throws IOException {
            if (revision <= lastSentRevision) {
                return;
            }
            sendFrame(0x01, text.getBytes(StandardCharsets.UTF_8));
            lastSentRevision = revision;
        }

        void closeUnauthorized() {
            // A raw close cannot wait behind a slow synchronized writer. The browser performs
            // one authenticated state probe after disconnect to distinguish expiry from outage.
            closeSocket();
        }

        void closeSocket() {
            ScheduledFuture<?> task;
            synchronized (sessionTaskLock) {
                closed = true;
                task = sessionExpiryTask;
                sessionExpiryTask = null;
            }
            if (task != null) {
                task.cancel(false);
            }
            closeQuietly(socket);
        }

        private synchronized void closeProtocolError() throws IOException {
            sendClose(1002);
        }

        private void sendClose(int code) throws IOException {
            byte[] payload = new byte[]{(byte) (code >> 8), (byte) code};
            sendFrame(0x08, payload);
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            if (closed) {
                throw new IOException("websocket is closed");
            }
            output.write(0x80 | opcode);
            if (payload.length <= 125) {
                output.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                output.write(126);
                output.write((payload.length >> 8) & 0xFF);
                output.write(payload.length & 0xFF);
            } else {
                output.write(127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) ((length >> shift) & 0xFFL));
                }
            }
            output.write(payload);
            output.flush();
        }
    }
}
