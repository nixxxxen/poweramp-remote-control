package dev.r4remote.poweramp.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates NSD, one-time token verification, event streaming, controls, and reconnect. */
final class RemoteClientController implements NsdDiscoveryClient.Listener, AutoCloseable {
    enum Status {
        SEARCHING, PAIRING, VERIFYING, CONNECTING, CONNECTED, RETRYING, AUTH_REQUIRED, ERROR
    }

    enum PairingError { INVALID_TOKEN, UNAUTHORIZED, NETWORK, STORAGE }

    interface Listener {
        void onStatusChanged(Status status, long retryDelayMilliseconds);
        void onPairingRequired(DiscoveredServer server, boolean tokenRejected);
        void onPairingFailed(PairingError error);
        void onPairingSucceeded(String serviceName);
        void onStateChanged(RemoteState state);
        void onArtworkChanged(Bitmap artwork);
        void onCommandError(boolean authenticationError);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PairingStore pairingStore;
    private final RemoteApiClient apiClient = new RemoteApiClient();
    private final NsdDiscoveryClient discoveryClient;
    private final Listener listener;
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "remote-controls");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "remote-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, DiscoveredServer> candidates = new LinkedHashMap<>();
    private final Runnable reconnectRunnable = this::runReconnect;
    private final Runnable discoveryRestartRunnable = this::restartDiscovery;

    private PairingCredentials credentials;
    private DiscoveredServer endpoint;
    private DiscoveredServer pairingCandidate;
    private RemoteWebSocket webSocket;
    private boolean active;
    private boolean closed;
    private boolean connecting;
    private boolean connected;
    private int connectionGeneration;
    private int operationGeneration;
    private int reconnectFailures;
    private int artworkGeneration;
    private long connectionLastRevision = -1L;
    private String artworkRequestKey;

    RemoteClientController(Context context, Listener listener) {
        this.listener = listener;
        pairingStore = new PairingStore(context.getApplicationContext());
        credentials = pairingStore.load();
        discoveryClient = new NsdDiscoveryClient(context.getApplicationContext(), this);
    }

    boolean hasPairing() {
        return credentials != null;
    }

    String pairedServiceName() {
        return credentials == null ? null : credentials.serviceName;
    }

    boolean isConnected() {
        return connected;
    }

    void start() {
        if (active || closed) return;
        active = true;
        notifyStatus(Status.SEARCHING, 0L);
        discoveryClient.start();
    }

    void stop() {
        if (!active) return;
        active = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        discoveryClient.stop();
        candidates.clear();
        pairingCandidate = null;
        endpoint = null;
        disconnectSocket();
    }

    void pair(DiscoveredServer server, String enteredToken) {
        if (!active || server == null) return;
        int operation = ++operationGeneration;
        String token = enteredToken == null ? "" : enteredToken.trim();
        if (!PairingCredentials.isValidToken(token)) {
            listener.onPairingFailed(PairingError.INVALID_TOKEN);
            return;
        }
        notifyStatus(Status.VERIFYING, 0L);
        try {
            controlExecutor.execute(() -> {
                try {
                    RemoteState state = apiClient.getState(server, token);
                    mainHandler.post(() -> finishPairing(operation, server, token, state));
                } catch (RemoteApiClient.HttpStatusException exception) {
                    PairingError error = exception.statusCode == 401
                            ? PairingError.UNAUTHORIZED : PairingError.NETWORK;
                    mainHandler.post(() -> failPairing(operation, error));
                } catch (IOException exception) {
                    mainHandler.post(() -> failPairing(operation, PairingError.NETWORK));
                }
            });
        } catch (RejectedExecutionException exception) {
            failPairing(operation, PairingError.NETWORK);
        }
    }

    boolean forgetPairing() {
        if (closed) return false;
        try {
            pairingStore.clear();
        } catch (IllegalStateException exception) {
            listener.onPairingFailed(PairingError.STORAGE);
            return false;
        }
        credentials = null;
        operationGeneration++;
        endpoint = null;
        disconnectSocket();
        artworkRequestKey = null;
        artworkGeneration++;
        listener.onArtworkChanged(null);
        pairingCandidate = firstCandidate();
        if (pairingCandidate != null) {
            notifyStatus(Status.PAIRING, 0L);
            listener.onPairingRequired(pairingCandidate, false);
        } else {
            notifyStatus(Status.SEARCHING, 0L);
        }
        return true;
    }

    void play() { sendControl(RemoteCommandJson.play()); }
    void pause() { sendControl(RemoteCommandJson.pause()); }
    void previous() { sendControl(RemoteCommandJson.previous()); }
    void next() { sendControl(RemoteCommandJson.next()); }
    void seek(int seconds) { sendControl(RemoteCommandJson.seek(seconds)); }
    void setRating(int rating) { sendControl(RemoteCommandJson.rating(rating)); }
    void setShuffle(boolean enabled) { sendControl(RemoteCommandJson.shuffle(enabled)); }

    @Override
    public void onServerFound(DiscoveredServer server) {
        if (!active) return;
        candidates.put(server.serverId, server);
        if (credentials == null) {
            if (pairingCandidate == null
                    || pairingCandidate.serverId.equals(server.serverId)) {
                pairingCandidate = server;
                notifyStatus(Status.PAIRING, 0L);
                listener.onPairingRequired(server, false);
            }
            return;
        }
        if (!credentials.serverId.equals(server.serverId)) return;
        if (endpoint != null && endpoint.sameEndpoint(server) && (connecting || connected)) return;
        endpoint = server;
        connectSocket(false);
    }

    @Override
    public void onServerLost(String serviceName) {
        if (!active) return;
        candidates.values().removeIf(server -> serviceName.equals(server.serviceName));
        if (pairingCandidate != null && serviceName.equals(pairingCandidate.serviceName)) {
            pairingCandidate = null;
        }
        if (endpoint != null && serviceName.equals(endpoint.serviceName)) {
            endpoint = null;
            disconnectSocket();
            notifyStatus(Status.SEARCHING, 0L);
        }
    }

    @Override
    public void onDiscoveryError(int errorCode) {
        if (!active) return;
        notifyStatus(Status.SEARCHING, ReconnectBackoff.delayMilliseconds(1));
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        mainHandler.postDelayed(discoveryRestartRunnable, ReconnectBackoff.delayMilliseconds(1));
    }

    private void finishPairing(
            int operation,
            DiscoveredServer server,
            String token,
            RemoteState state
    ) {
        if (!active || operation != operationGeneration) return;
        PairingCredentials paired = new PairingCredentials(server.serverId, server.serviceName, token);
        try {
            pairingStore.save(paired);
        } catch (IllegalStateException exception) {
            failPairing(operation, PairingError.STORAGE);
            return;
        }
        credentials = paired;
        pairingCandidate = null;
        endpoint = server;
        listener.onPairingSucceeded(server.serviceName);
        listener.onStateChanged(state);
        requestArtwork(state);
        connectSocket(false);
    }

    private void failPairing(int operation, PairingError error) {
        if (!active || operation != operationGeneration) return;
        notifyStatus(error == PairingError.UNAUTHORIZED
                ? Status.AUTH_REQUIRED : Status.PAIRING, 0L);
        listener.onPairingFailed(error);
    }

    private void connectSocket(boolean retry) {
        if (!active || credentials == null || endpoint == null) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        disconnectSocket();
        final int generation = ++connectionGeneration;
        final DiscoveredServer connectingEndpoint = endpoint;
        connectionLastRevision = -1L;
        connecting = true;
        connected = false;
        notifyStatus(retry ? Status.RETRYING : Status.CONNECTING, 0L);
        RemoteWebSocket socket = new RemoteWebSocket(
                connectingEndpoint,
                credentials.token,
                new RemoteWebSocket.Listener() {
                    @Override
                    public void onOpen() {
                        mainHandler.post(() -> handleSocketOpen(generation));
                    }

                    @Override
                    public void onState(RemoteState state) {
                        mainHandler.post(() -> handleSocketState(generation, state));
                    }

                    @Override
                    public void onFailure(RemoteWebSocket.FailureType type, Exception exception) {
                        mainHandler.post(() -> handleSocketFailure(
                                generation, connectingEndpoint, type
                        ));
                    }
                }
        );
        webSocket = socket;
        socket.start();
    }

    private void handleSocketOpen(int generation) {
        // API v1 sends a complete snapshot immediately after the upgrade. Keep controls
        // disabled until that snapshot is parsed, rather than exposing stale prior state.
    }

    private void handleSocketState(int generation, RemoteState state) {
        if (!isCurrentConnection(generation) || state.revision <= connectionLastRevision) return;
        boolean firstSnapshot = connectionLastRevision < 0L;
        connectionLastRevision = state.revision;
        reconnectFailures = 0;
        if (firstSnapshot) {
            connecting = false;
            connected = true;
            notifyStatus(Status.CONNECTED, 0L);
        }
        listener.onStateChanged(state);
        requestArtwork(state);
    }

    private void handleSocketFailure(
            int generation,
            DiscoveredServer failedEndpoint,
            RemoteWebSocket.FailureType type
    ) {
        if (!isCurrentConnection(generation)) return;
        webSocket = null;
        connecting = false;
        connected = false;
        if (type == RemoteWebSocket.FailureType.AUTHENTICATION) {
            invalidateRejectedCredentials(failedEndpoint);
            return;
        }
        long delay = ReconnectBackoff.delayMilliseconds(reconnectFailures++);
        notifyStatus(Status.RETRYING, delay);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void invalidateRejectedCredentials(DiscoveredServer failedEndpoint) {
        disconnectSocket();
        try {
            pairingStore.clear();
        } catch (IllegalStateException exception) {
            listener.onPairingFailed(PairingError.STORAGE);
        }
        credentials = null;
        operationGeneration++;
        pairingCandidate = failedEndpoint;
        notifyStatus(Status.AUTH_REQUIRED, 0L);
        listener.onPairingRequired(failedEndpoint, true);
    }

    private void sendControl(String commandJson) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            listener.onCommandError(false);
            return;
        }
        try {
            controlExecutor.execute(() -> {
                try {
                    apiClient.sendControl(currentEndpoint, currentCredentials.token, commandJson);
                } catch (RemoteApiClient.HttpStatusException exception) {
                    boolean authentication = exception.statusCode == 401;
                    mainHandler.post(() -> handleControlFailure(
                            currentEndpoint, currentCredentials, authentication
                    ));
                } catch (IOException exception) {
                    mainHandler.post(() -> handleControlFailure(
                            currentEndpoint, currentCredentials, false
                    ));
                }
            });
        } catch (RejectedExecutionException exception) {
            listener.onCommandError(false);
        }
    }

    private void handleControlFailure(
            DiscoveredServer failedEndpoint,
            PairingCredentials usedCredentials,
            boolean authentication
    ) {
        if (!active || credentials != usedCredentials || endpoint != failedEndpoint) return;
        if (authentication) invalidateRejectedCredentials(failedEndpoint);
        listener.onCommandError(authentication);
    }

    private void requestArtwork(RemoteState state) {
        String stateKey = state.artworkKey();
        if (stateKey == null) {
            if (artworkRequestKey != null) {
                artworkRequestKey = null;
                artworkGeneration++;
                listener.onArtworkChanged(null);
            }
            return;
        }
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        if (currentCredentials == null || currentEndpoint == null) return;
        String requestKey = currentEndpoint.addressLabel() + '\u0000' + stateKey;
        if (requestKey.equals(artworkRequestKey)) return;
        listener.onArtworkChanged(null);
        artworkRequestKey = requestKey;
        int generation = ++artworkGeneration;
        try {
            artworkExecutor.execute(() -> {
                try {
                    byte[] bytes = apiClient.getArtwork(
                            currentEndpoint,
                            currentCredentials.token,
                            state.artwork
                    );
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) throw new IOException("Unable to decode artwork");
                    mainHandler.post(() -> {
                        if (active && generation == artworkGeneration
                                && requestKey.equals(artworkRequestKey)) {
                            listener.onArtworkChanged(bitmap);
                        }
                    });
                } catch (IOException exception) {
                    mainHandler.post(() -> {
                        if (generation == artworkGeneration
                                && requestKey.equals(artworkRequestKey)) {
                            artworkRequestKey = null;
                        }
                    });
                }
            });
        } catch (RejectedExecutionException exception) {
            artworkRequestKey = null;
        }
    }

    private void runReconnect() {
        if (active && credentials != null && endpoint != null) connectSocket(true);
    }

    private void restartDiscovery() {
        if (!active) return;
        discoveryClient.stop();
        discoveryClient.start();
    }

    private DiscoveredServer firstCandidate() {
        return candidates.isEmpty() ? null : candidates.values().iterator().next();
    }

    private boolean isCurrentConnection(int generation) {
        return active && generation == connectionGeneration;
    }

    private void disconnectSocket() {
        connectionGeneration++;
        connecting = false;
        connected = false;
        RemoteWebSocket current = webSocket;
        webSocket = null;
        if (current != null) current.close();
    }

    private void notifyStatus(Status status, long retryDelayMilliseconds) {
        listener.onStatusChanged(status, retryDelayMilliseconds);
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
        discoveryClient.close();
        controlExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
