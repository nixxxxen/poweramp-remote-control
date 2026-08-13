package dev.powerampremote.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates discovery transports, token verification, event streaming, controls, and reconnect. */
final class RemoteClientController implements NsdDiscoveryClient.Listener,
        WifiDirectConnectionClient.Listener, AutoCloseable {
    private static final long DIRECT_FALLBACK_DELAY_MILLISECONDS = 8_000L;

    enum Status {
        SEARCHING,
        PAIRING,
        VERIFYING,
        CONNECTING,
        CONNECTED,
        DIRECT_SEARCHING,
        DIRECT_PERMISSION_REQUIRED,
        DIRECT_LOCATION_REQUIRED,
        DIRECT_WIFI_REQUIRED,
        DIRECT_CONNECTING,
        CONNECTED_DIRECT,
        DIRECT_UNSUPPORTED,
        DIRECT_ACTION_REQUIRED,
        RETRYING,
        AUTH_REQUIRED,
        ERROR
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
    private final WifiDirectConnectionClient directClient;
    private final ConnectivityManager connectivityManager;
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
    private final Runnable directFallbackRunnable = this::startDirectFallback;
    private final Runnable networkRecoveryRunnable = this::handleNetworkRecovery;
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleNetworkRecovery(true);
                }

                @Override
                public void onLost(Network network) {
                    scheduleNetworkRecovery(false);
                }
            };

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
    private boolean networkMonitorRegistered;
    private boolean recoveredNetworkAvailable;

    RemoteClientController(Context context, Listener listener) {
        this.listener = listener;
        pairingStore = new PairingStore(context.getApplicationContext());
        credentials = pairingStore.load();
        discoveryClient = new NsdDiscoveryClient(context.getApplicationContext(), this);
        directClient = new WifiDirectConnectionClient(context.getApplicationContext(), this);
        connectivityManager = context.getSystemService(ConnectivityManager.class);
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
        startNetworkMonitor();
        discoveryClient.start();
        scheduleDirectFallback(DIRECT_FALLBACK_DELAY_MILLISECONDS);
    }

    void stop() {
        if (!active) return;
        active = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        stopNetworkMonitor();
        discoveryClient.stop();
        directClient.stop();
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
        mainHandler.removeCallbacks(directFallbackRunnable);
        directClient.stop();
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

    void retryDirectConnection() {
        if (!active || credentials == null) return;
        restartDiscovery();
        if (directClient.isActive()) {
            directClient.retry();
        } else {
            directClient.start(credentials.serverId, credentials.serviceName);
        }
    }

    void retryLanDiscovery() {
        if (!active) return;
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
    }

    void onDirectPermissionOrSettingsChanged() {
        if (!active || credentials == null) return;
        if (directClient.isActive()) {
            directClient.onPermissionOrSettingsChanged();
        } else {
            scheduleDirectFallback(0L);
        }
    }

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
        if (endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                && endpoint.port == server.port
                && endpoint.address.equals(server.address)) {
            // NsdManager can surface the same server over the P2P interface. Keep the
            // established direct group instead of tearing down the route underneath it.
            return;
        }
        if (endpoint != null && endpoint.sameEndpoint(server) && (connecting || connected)) return;
        mainHandler.removeCallbacks(directFallbackRunnable);
        directClient.stop();
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
        if (endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.LAN
                && serviceName.equals(endpoint.serviceName)) {
            endpoint = null;
            disconnectSocket();
            notifyStatus(Status.SEARCHING, 0L);
            scheduleDirectFallback(0L);
        }
    }

    @Override
    public void onDiscoveryError(int errorCode) {
        if (!active) return;
        notifyStatus(Status.SEARCHING, ReconnectBackoff.delayMilliseconds(1));
        scheduleDirectFallback(0L);
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
        if (connectingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) {
            notifyStatus(Status.DIRECT_CONNECTING, 0L);
        } else {
            notifyStatus(retry ? Status.RETRYING : Status.CONNECTING, 0L);
        }
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
            if (endpoint != null && endpoint.transport == DiscoveredServer.Transport.LAN) {
                mainHandler.removeCallbacks(directFallbackRunnable);
                directClient.stop();
            }
            notifyStatus(endpoint != null
                            && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                            ? Status.CONNECTED_DIRECT : Status.CONNECTED,
                    0L);
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
        mainHandler.removeCallbacks(directFallbackRunnable);
        if (failedEndpoint.transport == DiscoveredServer.Transport.LAN) {
            directClient.stop();
        }
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

    @Override
    public void onDirectStatus(WifiDirectConnectionClient.State directState, int reason) {
        if (!active || credentials == null || connected) return;
        switch (directState) {
            case PERMISSION_REQUIRED:
                notifyStatus(Status.DIRECT_PERMISSION_REQUIRED, 0L);
                break;
            case LOCATION_DISABLED:
                notifyStatus(Status.DIRECT_LOCATION_REQUIRED, 0L);
                break;
            case WIFI_DISABLED:
                notifyStatus(Status.DIRECT_WIFI_REQUIRED, 0L);
                break;
            case DISCOVERING:
                notifyStatus(Status.DIRECT_SEARCHING, 0L);
                break;
            case CONNECTING:
            case WAITING_FOR_APPROVAL:
                notifyStatus(Status.DIRECT_CONNECTING, 0L);
                break;
            case UNSUPPORTED:
                notifyStatus(Status.DIRECT_UNSUPPORTED, 0L);
                break;
            case PHONE_GROUP_OWNER:
            case FAILED:
            default:
                notifyStatus(Status.DIRECT_ACTION_REQUIRED, 0L);
                break;
        }
    }

    @Override
    public void onDirectEndpoint(DiscoveredServer server) {
        if (!active || credentials == null
                || !credentials.serverId.equals(server.serverId)) {
            return;
        }
        if (endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.LAN
                && (connecting || connected)) {
            directClient.stop();
            return;
        }
        endpoint = server;
        connectSocket(false);
    }

    @Override
    public void onDirectDisconnected() {
        if (!active || endpoint == null
                || endpoint.transport != DiscoveredServer.Transport.WIFI_DIRECT) {
            return;
        }
        endpoint = null;
        disconnectSocket();
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
        scheduleDirectFallback(0L);
    }

    private void scheduleDirectFallback(long delayMilliseconds) {
        mainHandler.removeCallbacks(directFallbackRunnable);
        if (active && credentials != null && !connected) {
            mainHandler.postDelayed(directFallbackRunnable, Math.max(0L, delayMilliseconds));
        }
    }

    private void startDirectFallback() {
        if (!active || credentials == null || connected || directClient.isActive()) return;
        directClient.start(credentials.serverId, credentials.serviceName);
    }

    private void startNetworkMonitor() {
        if (networkMonitorRegistered || connectivityManager == null) return;
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler);
            networkMonitorRegistered = true;
        } catch (RuntimeException ignored) {
            networkMonitorRegistered = false;
        }
    }

    private void stopNetworkMonitor() {
        if (!networkMonitorRegistered || connectivityManager == null) return;
        networkMonitorRegistered = false;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // The platform may already have released the callback.
        }
    }

    private void scheduleNetworkRecovery(boolean networkAvailable) {
        recoveredNetworkAvailable = networkAvailable;
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        if (active) mainHandler.postDelayed(networkRecoveryRunnable, 500L);
    }

    private void handleNetworkRecovery() {
        if (!active) return;
        if (connectivityManager != null) {
            try {
                recoveredNetworkAvailable = connectivityManager.getActiveNetwork() != null;
            } catch (RuntimeException ignored) {
                // Keep the latest callback value when the platform cannot answer the query.
            }
        }
        if (!recoveredNetworkAvailable
                && endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.LAN) {
            endpoint = null;
            disconnectSocket();
            notifyStatus(Status.SEARCHING, 0L);
        }
        restartDiscovery();
        if (credentials != null && !connected) {
            scheduleDirectFallback(recoveredNetworkAvailable
                    ? DIRECT_FALLBACK_DELAY_MILLISECONDS : 0L);
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
        directClient.close();
        controlExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
