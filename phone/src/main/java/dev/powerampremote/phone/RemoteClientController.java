package dev.powerampremote.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates discovery transports, pairing, event streaming, controls, and reconnect. */
final class RemoteClientController implements NsdDiscoveryClient.Listener,
        WifiDirectConnectionClient.Listener, PairingRequest.Target, AutoCloseable {
    private static final String TAG = "RemoteClientController";
    private static final long DIRECT_FALLBACK_DELAY_MILLISECONDS = 8_000L;
    private static final long NETWORK_RECOVERY_DEBOUNCE_MILLISECONDS = 500L;
    private static final long MANUAL_PAIRING_TIMEOUT_MILLISECONDS = 15_000L;

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
        DIRECT_WAITING_APPROVAL,
        CONNECTED_DIRECT,
        DIRECT_UNSUPPORTED,
        DIRECT_ACTION_REQUIRED,
        RETRYING,
        AUTH_REQUIRED,
        SWITCHING,
        SERVER_UNAVAILABLE,
        ERROR
    }

    enum PairingError {
        INVALID_QR,
        INVALID_TOKEN,
        QR_REJECTED,
        TOKEN_REJECTED,
        NETWORK,
        MANUAL_NETWORK,
        STORAGE
    }

    enum PairingMode { NONE, QR, MANUAL_TOKEN }

    enum SwitchFailure { NONE, TIMEOUT, PERMISSION, LOCATION, WIFI, UNSUPPORTED, DIRECT_FAILED }

    enum LibraryFailure {
        DISCONNECTED,
        PERMISSION_REQUIRED,
        UNSUPPORTED,
        AUTHENTICATION,
        PROVIDER_UNAVAILABLE,
        PAGE_EXPIRED,
        SERVER_ERROR
    }

    enum LibraryArtworkFailure { MISSING, TRANSIENT_FAILURE }

    interface LibraryPageCallback {
        void onResult(int connectionGeneration, LibraryPage page, LibraryFailure failure);
    }

    interface LibraryCapabilitiesCallback {
        void onResult(
                int connectionGeneration,
                LibrarySortCapabilities capabilities,
                LibraryFailure failure
        );
    }

    interface CategorizedSearchCallback {
        void onResult(
                int connectionGeneration,
                CategorizedSearchResult result,
                LibraryFailure failure
        );
    }

    interface LibraryActionCallback {
        void onResult(int connectionGeneration, LibraryFailure failure);
    }

    interface QueueAddCallback {
        void onResult(
                int connectionGeneration,
                QueueAddResult result,
                LibraryFailure failure
        );
    }

    interface LibraryArtworkCallback {
        void onResult(
                int connectionGeneration,
                LibraryArtworkKey artworkKey,
                Bitmap artwork,
                LibraryArtworkFailure failure
        );
    }

    interface RepresentativeArtworkCallback {
        void onResult(
                int connectionGeneration,
                RepresentativeArtworkKey representativeKey,
                Bitmap artwork
        );
    }

    private static final class RepresentativeArtworkRequest {
        final String identity;
        final int generation;
        final RepresentativeArtworkKey key;
        final List<RepresentativeArtworkCallback> callbacks = new ArrayList<>();
        RepresentativeArtworkProbe probe;

        RepresentativeArtworkRequest(
                String identity,
                int generation,
                RepresentativeArtworkKey key
        ) {
            this.identity = identity;
            this.generation = generation;
            this.key = key;
        }
    }

    interface Listener {
        void onStatusChanged(Status status, long retryDelayMilliseconds);
        void onPairingFailed(PairingError error);
        void onPairingSucceeded(String serviceName);
        void onStateChanged(RemoteState state, long receivedRealtimeMilliseconds);
        void onArtworkChanged(Bitmap artwork);
        default void onArtworkBytesChanged(byte[] artwork) { }
        void onCommandError(boolean authenticationError);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PairingStore pairingStore;
    private final RemoteApiClient apiClient = new RemoteApiClient();
    private final LibraryArtworkRepository libraryArtworkRepository;
    private final RepresentativeArtworkCache representativeArtworkCache =
            new RepresentativeArtworkCache();
    private final NsdDiscoveryClient discoveryClient;
    private final WifiDirectConnectionClient directClient;
    private final ConnectivityManager connectivityManager;
    private final Listener listener;
    private final TransportSelectionPolicy transportSelection;
    private final RecoveryDeadline directFallbackDeadline = new RecoveryDeadline();
    private final RecoveryDeadline networkRecoveryDeadline = new RecoveryDeadline();
    private final RecoveryDeadline selectionDeadline = new RecoveryDeadline();
    private final Map<DiscoveredServer, Long> rejectedEndpoints = new HashMap<>();
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
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "remote-library");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService libraryArtworkExecutor = Executors.newFixedThreadPool(
            2,
            runnable -> {
                Thread thread = new Thread(runnable, "remote-library-artwork");
                thread.setDaemon(true);
                return thread;
            }
    );
    private final Map<String, DiscoveredServer> candidates = new LinkedHashMap<>();
    private final Map<String, List<LibraryArtworkCallback>> libraryArtworkRequests =
            new HashMap<>();
    private final Map<String, RepresentativeArtworkRequest> representativeArtworkRequests =
            new HashMap<>();
    private final LanNetworkTracker<Network> lanNetworkTracker = new LanNetworkTracker<>();
    private final Runnable reconnectRunnable = this::runReconnect;
    private final Runnable discoveryRestartRunnable = this::restartDiscovery;
    private final Runnable directFallbackRunnable = this::startDirectFallback;
    private final Runnable networkRecoveryRunnable = this::handleNetworkRecovery;
    private final Runnable manualPairingTimeoutRunnable = this::finishManualPairingTimeout;
    private final Runnable selectionTimeoutRunnable = this::handleSelectionTimeout;
    private final Runnable pathFailureTimeoutRunnable = this::handlePathFailureTimeout;
    private final Runnable qrPairingTimeoutRunnable = this::finishQrPairingTimeout;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int networkMonitorGeneration;

    private ConnectivityManager.NetworkCallback networkCallback(int monitorGeneration) {
        return new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (!active || monitorGeneration != networkMonitorGeneration) return;
                    updateTrackedNetwork(
                            network,
                            getNetworkCapabilities(network),
                            "network available"
                    );
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities networkCapabilities
                ) {
                    if (!active || monitorGeneration != networkMonitorGeneration) return;
                    updateTrackedNetwork(
                            network,
                            networkCapabilities,
                            "network capabilities changed"
                    );
                }

                @Override
                public void onLost(Network network) {
                    if (!active || monitorGeneration != networkMonitorGeneration) return;
                    boolean hadLanNetwork = lanNetworkTracker.hasLanNetwork();
                    if (lanNetworkTracker.remove(network)) {
                        scheduleNetworkRecovery(
                                "infrastructure LAN network lost",
                                endpoint != null && network.equals(endpoint.network) ? 0L
                                        : TransportRecoveryPolicy.networkRecoveryDelayMilliseconds(
                                        hadLanNetwork,
                                        lanNetworkTracker.hasLanNetwork(),
                                        NETWORK_RECOVERY_DEBOUNCE_MILLISECONDS
                                )
                        );
                    }
                }

            };
    }

    private PairingCredentials credentials;
    private DiscoveredServer endpoint;
    private PairingQrPayload pendingPairing;
    private String pendingManualToken;
    private final Set<String> attemptedManualServerIds = new HashSet<>();
    private RemoteWebSocket webSocket;
    private RemoteWebSocket pendingSocket;
    private DiscoveredServer pendingEndpoint;
    private DiscoveredServer directEndpoint;
    private final ConnectionAttemptGate socketAttempts = new ConnectionAttemptGate();
    private long attemptStartedMs;
    private long failureWindowStartedMs;
    private boolean directSuppressed;
    private boolean directNeedsUserRetry;
    private boolean authenticationRequired;
    private SwitchFailure switchFailure = SwitchFailure.NONE;
    private boolean active;
    private boolean closed;
    private boolean connecting;
    private boolean connected;
    private boolean pairingExchangeInFlight;
    private boolean manualPairingSawRejectedToken;
    private volatile int connectionGeneration;
    private final AtomicLong queueContentGeneration = new AtomicLong();
    private final AtomicBoolean queueAddInFlight = new AtomicBoolean();
    private volatile int operationGeneration;
    private int reconnectFailures;
    private int artworkGeneration;
    private long connectionLastRevision = -1L;
    private String artworkRequestKey;
    private boolean networkMonitorRegistered;
    private boolean lanNetworkAvailable;

    RemoteClientController(Context context, Listener listener) {
        this.listener = listener;
        pairingStore = new PairingStore(context.getApplicationContext());
        libraryArtworkRepository = new LibraryArtworkRepository(context.getApplicationContext());
        credentials = pairingStore.load();
        transportSelection = new TransportSelectionPolicy(new ConnectionPreferences(context).policy());
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

    String pairedDeviceName() {
        return credentials == null ? null : credentials.deviceName;
    }

    String pairedServerId() {
        return credentials == null ? null : credentials.serverId;
    }

    DiscoveredServer currentEndpoint() {
        return endpoint;
    }

    ConnectionPolicy connectionPolicy() { return transportSelection.policy(); }
    boolean isSwitchingTransport() { return transportSelection.switching(); }
    SwitchFailure switchFailure() { return switchFailure; }

    void changeConnectionPolicy(ConnectionPolicy policy) {
        if (policy == transportSelection.policy()) return;
        transportSelection.changePolicy(policy);
        switchFailure = SwitchFailure.NONE;
        directSuppressed = false;
        directNeedsUserRetry = false;
        cancelTransportTimers();
        cancelPendingSocket();
        operationGeneration++;
        if (isPairingInProgress()) {
            pendingPairing = null;
            mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
            clearManualPairingState();
            listener.onPairingFailed(PairingError.NETWORK);
        }
        pairingExchangeInFlight = false;
        record(ConnectionDiagnostics.Reason.POLICY_CHANGED, null,
                ConnectionDiagnostics.Stage.RUNTIME, ConnectionDiagnostics.Result.SUCCEEDED);
        if (!policy.allowsDirect()) {
            stopDirect();
            directEndpoint = null;
            if (endpoint != null && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) {
                disconnectSocket();
                endpoint = null;
                notifyStatus(Status.SEARCHING, 0L);
            }
        }
        if (!active) return;
        // A deliberate preference change starts a fresh bounded choice; healthy old API stays up.
        if (policy == ConnectionPolicy.DIRECT_PREFERRED) startPreferredDirectWindow();
        else {
            if (connected && endpoint.transport == DiscoveredServer.Transport.LAN) stopDirect();
            tryLanCandidate();
            if (!connected) scheduleDirectFallback(0L);
        }
        restartDiscovery();
    }

    boolean switchTransport(DiscoveredServer.Transport target) {
        if (!active || credentials == null || isPairingInProgress() || authenticationRequired
                || connected && endpoint != null && endpoint.transport == target
                || !transportSelection.beginManual(target)) return false;
        cancelTransportTimers();
        cancelPendingSocket();
        reconnectFailures = 0;
        failureWindowStartedMs = 0L;
        directSuppressed = false;
        directNeedsUserRetry = false;
        switchFailure = SwitchFailure.NONE;
        rejectedEndpoints.clear();
        attemptStartedMs = SystemClock.elapsedRealtime();
        record(ConnectionDiagnostics.Reason.MANUAL_SWITCH, target,
                ConnectionDiagnostics.Stage.CONNECT, ConnectionDiagnostics.Result.STARTED);
        notifyStatus(connected ? confirmedStatus() : Status.SWITCHING, 0L);
        scheduleSelectionTimeout();
        if (target == DiscoveredServer.Transport.WIFI_DIRECT) {
            scheduleDirectFallback(0L);
        } else {
            if (!connected || endpoint.transport != DiscoveredServer.Transport.WIFI_DIRECT) {
                stopDirect();
            }
            tryLanCandidate();
            restartDiscovery();
        }
        return true;
    }

    boolean isPairingInProgress() {
        return pendingPairing != null || pendingManualToken != null;
    }

    PairingMode pairingMode() {
        if (pendingPairing != null) return PairingMode.QR;
        if (pendingManualToken != null) return PairingMode.MANUAL_TOKEN;
        return PairingMode.NONE;
    }

    void start() {
        if (active || closed) return;
        active = true;
        record(ConnectionDiagnostics.Reason.START, null,
                ConnectionDiagnostics.Stage.RUNTIME, ConnectionDiagnostics.Result.STARTED);
        notifyStatus(Status.SEARCHING, 0L);
        startNetworkMonitor();
        discoveryClient.start();
        if (transportSelection.policy() == ConnectionPolicy.DIRECT_PREFERRED) {
            startPreferredDirectWindow();
        } else {
            scheduleDirectFallback(TransportRecoveryPolicy.directFallbackDelayMilliseconds(
                    hasLanCapableNetwork(), DIRECT_FALLBACK_DELAY_MILLISECONDS));
        }
    }

    void stop() {
        if (!active) return;
        Log.i(TAG, "Client runtime explicitly stopping");
        active = false;
        operationGeneration++;
        cancelTransportTimers();
        transportSelection.reset();
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        mainHandler.removeCallbacks(manualPairingTimeoutRunnable);
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        stopNetworkMonitor();
        discoveryClient.stop();
        stopDirect();
        candidates.clear();
        pendingPairing = null;
        pendingManualToken = null;
        attemptedManualServerIds.clear();
        pairingExchangeInFlight = false;
        endpoint = null;
        disconnectSocket();
        record(ConnectionDiagnostics.Reason.STOP, null,
                ConnectionDiagnostics.Stage.RUNTIME, ConnectionDiagnostics.Result.CANCELLED);
    }

    @Override
    public void pairQr(PairingQrPayload payload) {
        if (!active || payload == null) {
            listener.onPairingFailed(PairingError.INVALID_QR);
            return;
        }
        operationGeneration++;
        resetTransportChoice();
        pendingPairing = payload;
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        mainHandler.postDelayed(qrPairingTimeoutRunnable, 120_000L);
        clearManualPairingState();
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        disconnectSocket();
        stopDirect();
        endpoint = null;
        notifyStatus(Status.PAIRING, 0L);
        DiscoveredServer candidate = candidates.get(payload.serverId);
        if (candidate != null && transportSelection.maySelect(candidate.transport, null, false)) {
            beginPairingExchange(candidate);
        } else {
            restartDiscovery();
            if (transportSelection.policy() == ConnectionPolicy.DIRECT_PREFERRED) {
                startPreferredDirectWindow();
            } else scheduleDirectFallback(DIRECT_FALLBACK_DELAY_MILLISECONDS);
        }
    }

    @Override
    public void pairManually(String enteredToken) {
        String token = enteredToken == null ? "" : enteredToken.trim();
        if (!active || !PairingCredentials.isValidToken(token)) {
            listener.onPairingFailed(PairingError.INVALID_TOKEN);
            return;
        }
        operationGeneration++;
        resetTransportChoice();
        pendingPairing = null;
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        pendingManualToken = token;
        attemptedManualServerIds.clear();
        manualPairingSawRejectedToken = false;
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        mainHandler.removeCallbacks(manualPairingTimeoutRunnable);
        disconnectSocket();
        stopDirect();
        endpoint = null;
        notifyStatus(Status.PAIRING, 0L);
        restartDiscovery();
        beginNextManualPairingVerification();
        mainHandler.postDelayed(
                manualPairingTimeoutRunnable,
                MANUAL_PAIRING_TIMEOUT_MILLISECONDS
        );
    }

    boolean forgetPairing() {
        if (closed) return false;
        try {
            pairingStore.clear();
        } catch (IllegalStateException exception) {
            listener.onPairingFailed(PairingError.STORAGE);
            return false;
        }
        libraryArtworkRepository.forgetAll();
        libraryArtworkRequests.clear();
        representativeArtworkCache.clear();
        representativeArtworkRequests.clear();
        try {
            libraryArtworkExecutor.execute(libraryArtworkRepository::clearDisk);
        } catch (RejectedExecutionException ignored) {
            // Service teardown can race an explicit Forget; memory is already cleared.
        }
        credentials = null;
        operationGeneration++;
        resetTransportChoice();
        pendingPairing = null;
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        clearManualPairingState();
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(directFallbackRunnable);
        stopDirect();
        endpoint = null;
        disconnectSocket();
        artworkRequestKey = null;
        artworkGeneration++;
        listener.onArtworkChanged(null);
        listener.onArtworkBytesChanged(null);
        notifyStatus(Status.SEARCHING, 0L);
        return true;
    }

    void play() { sendControl(RemoteCommandJson.play()); }
    void pause() { sendControl(RemoteCommandJson.pause()); }
    void previous() { sendControl(RemoteCommandJson.previous()); }
    void next() { sendControl(RemoteCommandJson.next()); }
    void seek(int seconds) { sendControl(RemoteCommandJson.seek(seconds)); }
    void setRating(int rating) { sendControl(RemoteCommandJson.rating(rating)); }
    void setShuffle(boolean enabled) { sendControl(RemoteCommandJson.shuffle(enabled)); }
    void setVolume(int volume) { sendControl(RemoteCommandJson.volume(volume)); }

    int libraryConnectionGeneration() {
        return connectionGeneration;
    }

    long queueContentGeneration() {
        return queueContentGeneration.get();
    }

    void requestLibraryPage(
            LibraryRequest request,
            String pageToken,
            LibraryPageCallback callback
    ) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, null, LibraryFailure.DISCONNECTED);
            return;
        }
        try {
            libraryExecutor.execute(() -> {
                LibraryPage page = null;
                LibraryFailure failure = null;
                try {
                    page = apiClient.getLibraryPage(
                            currentEndpoint,
                            currentCredentials.token,
                            request,
                            pageToken
                    );
                } catch (RemoteApiClient.HttpStatusException exception) {
                    failure = exception.statusCode == 400 && pageToken != null
                            ? LibraryFailure.PAGE_EXPIRED : libraryFailure(exception.statusCode);
                } catch (IOException | RuntimeException exception) {
                    failure = LibraryFailure.SERVER_ERROR;
                }
                LibraryPage resultPage = page;
                LibraryFailure resultFailure = failure;
                mainHandler.post(() -> callback.onResult(
                        generation, resultPage, resultFailure
                ));
            });
        } catch (RejectedExecutionException exception) {
            callback.onResult(generation, null, LibraryFailure.SERVER_ERROR);
        }
    }

    void requestLibraryCapabilities(LibraryCapabilitiesCallback callback) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, null, LibraryFailure.DISCONNECTED);
            return;
        }
        try {
            libraryExecutor.execute(() -> {
                LibrarySortCapabilities capabilities = null;
                LibraryFailure failure = null;
                try {
                    capabilities = apiClient.getLibraryCapabilities(
                            currentEndpoint, currentCredentials.token
                    );
                } catch (RemoteApiClient.HttpStatusException exception) {
                    failure = libraryFailure(exception.statusCode);
                } catch (IOException | RuntimeException exception) {
                    failure = LibraryFailure.SERVER_ERROR;
                }
                LibrarySortCapabilities deliveredCapabilities = capabilities;
                LibraryFailure deliveredFailure = failure;
                mainHandler.post(() -> callback.onResult(
                        generation, deliveredCapabilities, deliveredFailure
                ));
            });
        } catch (RejectedExecutionException exception) {
            callback.onResult(generation, null, LibraryFailure.SERVER_ERROR);
        }
    }

    void requestCategorizedSearch(
            CategorizedSearchRequest request,
            CategorizedSearchCallback callback
    ) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, null, LibraryFailure.DISCONNECTED);
            return;
        }
        try {
            libraryExecutor.execute(() -> {
                CategorizedSearchResult result = null;
                LibraryFailure failure = null;
                try {
                    result = apiClient.getCategorizedSearch(
                            currentEndpoint,
                            currentCredentials.token,
                            request
                    );
                } catch (RemoteApiClient.HttpStatusException exception) {
                    failure = exception.statusCode == 400 && request.isContinuation()
                            ? LibraryFailure.PAGE_EXPIRED
                            : libraryFailure(exception.statusCode);
                } catch (IOException | RuntimeException exception) {
                    failure = LibraryFailure.SERVER_ERROR;
                }
                CategorizedSearchResult deliveredResult = result;
                LibraryFailure deliveredFailure = failure;
                mainHandler.post(() -> callback.onResult(
                        generation, deliveredResult, deliveredFailure
                ));
            });
        } catch (RejectedExecutionException exception) {
            callback.onResult(generation, null, LibraryFailure.SERVER_ERROR);
        }
    }

    void playLibraryTarget(LibraryPlayTarget target, LibraryActionCallback callback) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, LibraryFailure.DISCONNECTED);
            return;
        }
        try {
            libraryExecutor.execute(() -> {
                LibraryFailure failure = null;
                try {
                    if (!requestStillCurrent(generation)) throw new IOException("Cancelled before dispatch");
                    apiClient.playLibraryTarget(
                            currentEndpoint, currentCredentials.token, target
                    );
                } catch (RemoteApiClient.HttpStatusException exception) {
                    failure = libraryFailure(exception.statusCode);
                } catch (IOException | RuntimeException exception) {
                    failure = LibraryFailure.SERVER_ERROR;
                }
                LibraryFailure resultFailure = failure;
                mainHandler.post(() -> callback.onResult(generation, resultFailure));
            });
        } catch (RejectedExecutionException exception) {
            callback.onResult(generation, LibraryFailure.SERVER_ERROR);
        }
    }

    void addToQueue(QueueAddRequest request, QueueAddCallback callback) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, null, LibraryFailure.DISCONNECTED);
            return;
        }
        if (!queueAddInFlight.compareAndSet(false, true)) {
            callback.onResult(generation, null, LibraryFailure.SERVER_ERROR);
            return;
        }
        try {
            libraryExecutor.execute(() -> {
                QueueAddResult result = null;
                LibraryFailure failure = null;
                try {
                    if (!requestStillCurrent(generation)) throw new IOException("Cancelled before dispatch");
                    result = apiClient.addToQueue(
                            currentEndpoint, currentCredentials.token, request
                    );
                    if (result.addedCount > 0) queueContentGeneration.incrementAndGet();
                } catch (RemoteApiClient.HttpStatusException exception) {
                    failure = libraryFailure(exception.statusCode);
                } catch (IOException | RuntimeException exception) {
                    failure = LibraryFailure.SERVER_ERROR;
                }
                QueueAddResult deliveredResult = result;
                LibraryFailure deliveredFailure = failure;
                queueAddInFlight.set(false);
                mainHandler.post(() -> callback.onResult(
                        generation, deliveredResult, deliveredFailure
                ));
            });
        } catch (RejectedExecutionException exception) {
            queueAddInFlight.set(false);
            callback.onResult(generation, null, LibraryFailure.SERVER_ERROR);
        }
    }

    LibraryArtworkKey libraryArtworkKey(String artworkPath) {
        PairingCredentials currentCredentials = credentials;
        if (currentCredentials == null) return null;
        try {
            return LibraryArtworkKey.create(currentCredentials.serverId, artworkPath);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    Bitmap cachedLibraryArtwork(LibraryArtworkKey key) {
        PairingCredentials currentCredentials = credentials;
        if (key == null || currentCredentials == null
                || !currentCredentials.serverId.equals(key.serverId)) return null;
        return libraryArtworkRepository.memory(key, System.currentTimeMillis());
    }

    void requestLibraryArtwork(String artworkPath, LibraryArtworkCallback callback) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        LibraryArtworkKey key = libraryArtworkKey(artworkPath);
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            callback.onResult(generation, key, null, LibraryArtworkFailure.TRANSIENT_FAILURE);
            return;
        }
        if (key == null) {
            callback.onResult(generation, null, null, LibraryArtworkFailure.MISSING);
            return;
        }
        long now = System.currentTimeMillis();
        Bitmap cached = libraryArtworkRepository.memory(key, now);
        if (cached != null) {
            callback.onResult(generation, key, cached, null);
            return;
        }
        LibraryArtworkRepository.Suppression suppression =
                libraryArtworkRepository.suppression(key, now);
        if (suppression != null) {
            callback.onResult(generation, key, null, artworkFailure(suppression));
            return;
        }
        String requestIdentity = generation + "\u0000" + key.stableValue();
        List<LibraryArtworkCallback> callbacks = libraryArtworkRequests.get(requestIdentity);
        if (callbacks != null) {
            callbacks.add(callback);
            return;
        }
        callbacks = new ArrayList<>();
        callbacks.add(callback);
        libraryArtworkRequests.put(requestIdentity, callbacks);
        long requestEpoch = libraryArtworkRepository.epoch();
        try {
            libraryArtworkExecutor.execute(() -> loadLibraryArtwork(
                    requestIdentity,
                    generation,
                    key,
                    currentEndpoint,
                    currentCredentials,
                    requestEpoch
            ));
        } catch (RejectedExecutionException exception) {
            libraryArtworkRequests.remove(requestIdentity);
            callback.onResult(
                    generation, key, null, LibraryArtworkFailure.TRANSIENT_FAILURE
            );
        }
    }

    private void loadLibraryArtwork(
            String requestIdentity,
            int generation,
            LibraryArtworkKey key,
            DiscoveredServer requestEndpoint,
            PairingCredentials requestCredentials,
            long requestEpoch
    ) {
        Bitmap bitmap = null;
        LibraryArtworkFailure failure = null;
        long now = System.currentTimeMillis();
        LibraryArtworkDiskCache.Entry diskEntry = libraryArtworkRepository.disk(key, now);
        if (diskEntry != null) {
            try {
                bitmap = decodeLibraryArtwork(diskEntry.bytes);
                libraryArtworkRepository.storeMemory(
                        key, bitmap, diskEntry.createdAtMilliseconds, requestEpoch
                );
            } catch (IOException | RuntimeException exception) {
                libraryArtworkRepository.removeDisk(key);
                bitmap = null;
            }
        }
        LibraryArtworkRepository.Suppression suppression =
                libraryArtworkRepository.suppression(key, now);
        if (bitmap == null && suppression != null) {
            failure = artworkFailure(suppression);
        }
        if (bitmap == null && failure == null) {
            try {
                byte[] downloaded = apiClient.getLibraryArtwork(
                        requestEndpoint, requestCredentials.token, key.artworkPath
                );
                bitmap = decodeLibraryArtwork(downloaded);
                long createdAt = System.currentTimeMillis();
                libraryArtworkRepository.storeMemory(key, bitmap, createdAt, requestEpoch);
                try {
                    byte[] encoded = encodeLibraryArtwork(bitmap);
                    libraryArtworkRepository.storeDisk(
                            key, encoded, createdAt, createdAt, requestEpoch
                    );
                } catch (IOException | RuntimeException ignored) {
                    // Memory remains useful when this optional private-cache write fails.
                }
            } catch (RemoteApiClient.HttpStatusException exception) {
                if (exception.statusCode == 404) {
                    libraryArtworkRepository.suppressMissing(key, System.currentTimeMillis());
                    failure = LibraryArtworkFailure.MISSING;
                } else {
                    libraryArtworkRepository.suppressTransient(key, System.currentTimeMillis());
                    failure = LibraryArtworkFailure.TRANSIENT_FAILURE;
                }
                bitmap = null;
            } catch (IOException | RuntimeException exception) {
                libraryArtworkRepository.suppressTransient(key, System.currentTimeMillis());
                failure = LibraryArtworkFailure.TRANSIENT_FAILURE;
                bitmap = null;
            }
        }
        Bitmap result = bitmap;
        LibraryArtworkFailure resultFailure = failure;
        mainHandler.post(() -> completeLibraryArtwork(
                requestIdentity, generation, key, result, resultFailure
        ));
    }

    private void completeLibraryArtwork(
            String requestIdentity,
            int generation,
            LibraryArtworkKey key,
            Bitmap bitmap,
            LibraryArtworkFailure failure
    ) {
        List<LibraryArtworkCallback> callbacks = libraryArtworkRequests.remove(requestIdentity);
        if (callbacks == null) return;
        for (LibraryArtworkCallback callback : callbacks) {
            callback.onResult(generation, key, bitmap, failure);
        }
    }

    private static LibraryArtworkFailure artworkFailure(
            LibraryArtworkRepository.Suppression suppression
    ) {
        return suppression == LibraryArtworkRepository.Suppression.MISSING
                ? LibraryArtworkFailure.MISSING
                : LibraryArtworkFailure.TRANSIENT_FAILURE;
    }

    RepresentativeArtworkKey representativeArtworkKey(String categoryType, long categoryId) {
        PairingCredentials currentCredentials = credentials;
        if (currentCredentials == null) return null;
        try {
            return RepresentativeArtworkKey.create(
                    currentCredentials.serverId, categoryType, categoryId
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    Bitmap cachedRepresentativeArtwork(RepresentativeArtworkKey key) {
        PairingCredentials currentCredentials = credentials;
        if (!matchesCurrentServer(key, currentCredentials)) return null;
        RepresentativeArtworkCache.Mapping mapping = representativeArtworkCache.get(
                key, System.currentTimeMillis()
        );
        if (mapping == null || mapping.state != RepresentativeArtworkCache.State.SELECTED) {
            return null;
        }
        return libraryArtworkRepository.memory(
                mapping.artworkKey, System.currentTimeMillis()
        );
    }

    void rememberRepresentativeCandidates(
            RepresentativeArtworkKey key,
            List<LibraryItem> items
    ) {
        PairingCredentials currentCredentials = credentials;
        if (!matchesCurrentServer(key, currentCredentials) || items == null) return;
        try {
            representativeArtworkCache.rememberCandidates(
                    key,
                    RepresentativeArtworkProbe.candidates(key.serverId, items),
                    System.currentTimeMillis()
            );
        } catch (IllegalArgumentException ignored) {
            // Optional presentation hints never affect the containing Library page.
        }
    }

    void requestRepresentativeArtwork(
            RepresentativeArtworkKey key,
            RepresentativeArtworkCallback callback
    ) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || !matchesCurrentServer(key, currentCredentials)
                || currentEndpoint == null) {
            callback.onResult(generation, key, null);
            return;
        }
        long now = System.currentTimeMillis();
        RepresentativeArtworkCache.Mapping mapping = representativeArtworkCache.get(key, now);
        if (mapping != null) {
            if (mapping.state == RepresentativeArtworkCache.State.SELECTED) {
                requestSelectedRepresentativeArtwork(key, mapping.artworkKey, callback);
            } else {
                callback.onResult(generation, key, null);
            }
            return;
        }
        beginRepresentativeArtworkResolution(
                key, callback, currentCredentials, currentEndpoint, generation
        );
    }

    private void requestSelectedRepresentativeArtwork(
            RepresentativeArtworkKey representativeKey,
            LibraryArtworkKey artworkKey,
            RepresentativeArtworkCallback callback
    ) {
        requestLibraryArtwork(
                artworkKey.artworkPath,
                (generation, resultKey, bitmap, failure) -> {
                    if (generation != connectionGeneration
                            || !matchesCurrentServer(representativeKey, credentials)) {
                        callback.onResult(generation, representativeKey, null);
                        return;
                    }
                    if (bitmap != null) {
                        callback.onResult(generation, representativeKey, bitmap);
                        return;
                    }
                    if (failure == LibraryArtworkFailure.MISSING) {
                        representativeArtworkCache.invalidate(representativeKey);
                        requestRepresentativeArtwork(representativeKey, callback);
                    } else {
                        callback.onResult(generation, representativeKey, null);
                    }
                }
        );
    }

    private void beginRepresentativeArtworkResolution(
            RepresentativeArtworkKey key,
            RepresentativeArtworkCallback callback,
            PairingCredentials requestCredentials,
            DiscoveredServer requestEndpoint,
            int generation
    ) {
        String identity = generation + "\u0000" + key.stableValue();
        RepresentativeArtworkRequest existing = representativeArtworkRequests.get(identity);
        if (existing != null) {
            existing.callbacks.add(callback);
            return;
        }
        RepresentativeArtworkRequest request = new RepresentativeArtworkRequest(
                identity, generation, key
        );
        request.callbacks.add(callback);
        representativeArtworkRequests.put(identity, request);

        List<LibraryArtworkKey> candidates = representativeArtworkCache.candidates(
                key, System.currentTimeMillis()
        );
        if (candidates != null) {
            startRepresentativeProbe(request, candidates);
            return;
        }
        try {
            libraryArtworkExecutor.execute(() -> loadRepresentativeCandidates(
                    request,
                    requestCredentials,
                    requestEndpoint
            ));
        } catch (RejectedExecutionException exception) {
            representativeArtworkCache.transientFailure(key, System.currentTimeMillis());
            completeRepresentativeArtwork(request, null);
        }
    }

    private void loadRepresentativeCandidates(
            RepresentativeArtworkRequest request,
            PairingCredentials requestCredentials,
            DiscoveredServer requestEndpoint
    ) {
        LibraryPage page = null;
        boolean failed = false;
        try {
            page = apiClient.getLibraryPage(
                    requestEndpoint,
                    requestCredentials.token,
                    request.key.tracksRequest(RepresentativeArtworkProbe.MAXIMUM_TRACKS),
                    null
            );
        } catch (IOException | RuntimeException exception) {
            failed = true;
        }
        LibraryPage resultPage = page;
        boolean resultFailed = failed;
        mainHandler.post(() -> acceptRepresentativeCandidates(
                request, resultPage, resultFailed
        ));
    }

    private void acceptRepresentativeCandidates(
            RepresentativeArtworkRequest request,
            LibraryPage page,
            boolean failed
    ) {
        if (representativeArtworkRequests.get(request.identity) != request) return;
        if (request.generation != connectionGeneration
                || !matchesCurrentServer(request.key, credentials)) {
            completeRepresentativeArtwork(request, null);
            return;
        }
        if (failed || page == null) {
            representativeArtworkCache.transientFailure(
                    request.key, System.currentTimeMillis()
            );
            completeRepresentativeArtwork(request, null);
            return;
        }
        List<LibraryArtworkKey> candidates;
        try {
            candidates = RepresentativeArtworkProbe.candidates(
                    request.key.serverId, page.items
            );
            representativeArtworkCache.rememberCandidates(
                    request.key, candidates, System.currentTimeMillis()
            );
        } catch (IllegalArgumentException exception) {
            representativeArtworkCache.transientFailure(
                    request.key, System.currentTimeMillis()
            );
            completeRepresentativeArtwork(request, null);
            return;
        }
        startRepresentativeProbe(request, candidates);
    }

    private void startRepresentativeProbe(
            RepresentativeArtworkRequest request,
            List<LibraryArtworkKey> candidates
    ) {
        if (representativeArtworkRequests.get(request.identity) != request) return;
        request.probe = new RepresentativeArtworkProbe(candidates);
        if (request.probe.current() == null) {
            representativeArtworkCache.missing(request.key, System.currentTimeMillis());
            completeRepresentativeArtwork(request, null);
            return;
        }
        requestRepresentativeCandidate(request);
    }

    private void requestRepresentativeCandidate(RepresentativeArtworkRequest request) {
        LibraryArtworkKey candidate = request.probe.current();
        if (candidate == null) {
            representativeArtworkCache.missing(request.key, System.currentTimeMillis());
            completeRepresentativeArtwork(request, null);
            return;
        }
        requestLibraryArtwork(
                candidate.artworkPath,
                (generation, resultKey, bitmap, failure) -> acceptRepresentativeCandidate(
                        request, generation, resultKey, bitmap, failure
                )
        );
    }

    private void acceptRepresentativeCandidate(
            RepresentativeArtworkRequest request,
            int generation,
            LibraryArtworkKey candidate,
            Bitmap bitmap,
            LibraryArtworkFailure failure
    ) {
        if (representativeArtworkRequests.get(request.identity) != request) return;
        if (generation != request.generation || generation != connectionGeneration
                || !matchesCurrentServer(request.key, credentials)) {
            completeRepresentativeArtwork(request, null);
            return;
        }
        RepresentativeArtworkProbe.Outcome outcome = bitmap != null
                ? RepresentativeArtworkProbe.Outcome.AVAILABLE
                : failure == LibraryArtworkFailure.MISSING
                        ? RepresentativeArtworkProbe.Outcome.MISSING
                        : RepresentativeArtworkProbe.Outcome.TRANSIENT_FAILURE;
        RepresentativeArtworkProbe.Decision decision = request.probe.apply(candidate, outcome);
        switch (decision) {
            case SELECTED:
                representativeArtworkCache.selected(
                        request.key, candidate, System.currentTimeMillis()
                );
                completeRepresentativeArtwork(request, bitmap);
                break;
            case TRY_NEXT:
                requestRepresentativeCandidate(request);
                break;
            case EXHAUSTED:
                representativeArtworkCache.missing(request.key, System.currentTimeMillis());
                completeRepresentativeArtwork(request, null);
                break;
            case STOPPED:
            case STALE:
            default:
                representativeArtworkCache.transientFailure(
                        request.key, System.currentTimeMillis()
                );
                completeRepresentativeArtwork(request, null);
                break;
        }
    }

    private void completeRepresentativeArtwork(
            RepresentativeArtworkRequest request,
            Bitmap bitmap
    ) {
        if (representativeArtworkRequests.remove(request.identity) != request) return;
        for (RepresentativeArtworkCallback callback : request.callbacks) {
            callback.onResult(request.generation, request.key, bitmap);
        }
    }

    private static boolean matchesCurrentServer(
            RepresentativeArtworkKey key,
            PairingCredentials currentCredentials
    ) {
        return key != null && currentCredentials != null
                && currentCredentials.serverId.equals(key.serverId);
    }

    private static LibraryFailure libraryFailure(int statusCode) {
        if (statusCode == 401) return LibraryFailure.AUTHENTICATION;
        if (statusCode == 403) return LibraryFailure.PERMISSION_REQUIRED;
        if (statusCode == 404 || statusCode == 405) return LibraryFailure.UNSUPPORTED;
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return LibraryFailure.PROVIDER_UNAVAILABLE;
        }
        return LibraryFailure.SERVER_ERROR;
    }

    private static Bitmap decodeLibraryArtwork(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unable to inspect library artwork");
        }
        int sample = 1;
        while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Unable to decode library artwork");
        return bitmap;
    }

    private static byte[] encodeLibraryArtwork(Bitmap bitmap) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                throw new IOException("Unable to encode library artwork");
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0
                    || bytes.length > LibraryArtworkRepository.MAXIMUM_DISK_ENTRY_BYTES) {
                throw new IOException("Encoded library artwork is too large");
            }
            return bytes;
        }
    }

    void retryDirectConnection() {
        if (!active || targetServerId() == null || !transportSelection.allowsDirect()
                || authenticationRequired) return;
        directSuppressed = false;
        directNeedsUserRetry = false;
        switchFailure = SwitchFailure.NONE;
        rejectedEndpoints.clear();
        restartDiscovery();
        scheduleSelectionTimeout();
        if (directClient.isActive()) {
            directClient.retry();
        } else {
            directClient.start(targetServerId(), targetServiceName());
        }
    }

    void retryLanDiscovery() {
        if (!active) return;
        rejectedEndpoints.clear();
        transportSelection.allowFallback();
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
        tryLanCandidate();
    }

    void onDirectPermissionOrSettingsChanged() {
        if (!active || targetServerId() == null || !transportSelection.allowsDirect()
                || authenticationRequired) return;
        directSuppressed = false;
        directNeedsUserRetry = false;
        if (directClient.isActive()) {
            directClient.onPermissionOrSettingsChanged();
        } else {
            scheduleDirectFallback(0L);
        }
    }

    @Override
    public void onServerFound(DiscoveredServer server) {
        if (!active) return;
        // NSD also sees the P2P interface. It is not evidence of infrastructure LAN.
        if (!hasLanCapableNetwork() || isDirectAddress(server)) return;
        record(ConnectionDiagnostics.Reason.ENDPOINT_FOUND, server.transport,
                ConnectionDiagnostics.Stage.DISCOVERY, ConnectionDiagnostics.Result.SUCCEEDED);
        candidates.put(server.serverId, server);
        if (pendingPairing != null) {
            if (pendingPairing.serverId.equals(server.serverId)) {
                if (!transportSelection.maySelect(server.transport, null, false)) return;
                beginPairingExchange(server);
            }
            return;
        }
        if (pendingManualToken != null) {
            beginNextManualPairingVerification();
            return;
        }
        if (credentials == null) return;
        if (!credentials.matches(server)) return;
        if (endpoint != null && endpoint.sameEndpoint(server) && (connecting || connected)) return;
        // A Server returning after an exhausted, disconnected cycle gets a fresh bounded choice,
        // not the previous attempt's fallback/suppression. A healthy LAN fallback never gets here.
        beginPreferredDirectRecovery();
        tryDirectCandidate();
        tryLanCandidate();
    }

    @Override
    public void onServerLost(String serviceName) {
        if (!active) return;
        Log.i(TAG, "LAN NSD service lost");
        candidates.values().removeIf(server -> serviceName.equals(server.serviceName));
        // A disappearing mDNS record alone must not destroy a healthy authenticated socket.
        if (connected || connecting) return;
        if (endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.LAN
                && serviceName.equals(endpoint.serviceName)) {
            endpoint = null;
            disconnectSocket();
            transportSelection.unavailable(DiscoveredServer.Transport.LAN);
            notifyStatus(Status.SEARCHING, 0L);
            scheduleDirectFallback(0L);
        }
    }

    @Override
    public void onDiscoveryError(int errorCode) {
        if (!active) return;
        Log.w(TAG, "LAN NSD discovery failed: " + errorCode);
        if (!connected && !connecting) {
            notifyStatus(Status.SEARCHING, ReconnectBackoff.delayMilliseconds(1));
        }
        scheduleDirectFallback(0L);
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        mainHandler.postDelayed(discoveryRestartRunnable, ReconnectBackoff.delayMilliseconds(1));
    }

    private void beginPairingExchange(DiscoveredServer server) {
        PairingQrPayload offer = pendingPairing;
        if (!active || offer == null || pairingExchangeInFlight
                || !offer.serverId.equals(server.serverId)) {
            return;
        }
        int operation = operationGeneration;
        pairingExchangeInFlight = true;
        endpoint = server;
        notifyStatus(Status.VERIFYING, 0L);
        try {
            controlExecutor.execute(() -> {
                if (!active || operation != operationGeneration) return;
                try {
                    PairingExchangeResponse response = apiClient.exchangePairing(server, offer);
                    mainHandler.post(() -> finishPairing(operation, server, response));
                } catch (RemoteApiClient.HttpStatusException exception) {
                    PairingError error = exception.statusCode == 401
                            ? PairingError.QR_REJECTED : PairingError.NETWORK;
                    mainHandler.post(() -> failPairing(operation, error));
                } catch (IOException exception) {
                    mainHandler.post(() -> failPairing(operation, PairingError.NETWORK));
                }
            });
        } catch (RejectedExecutionException exception) {
            failPairing(operation, PairingError.NETWORK);
        }
    }

    private void finishPairing(
            int operation,
            DiscoveredServer server,
            PairingExchangeResponse response
    ) {
        if (!active || operation != operationGeneration || pendingPairing == null) return;
        PairingCredentials paired = new PairingCredentials(
                server.serverId,
                server.serviceName,
                response.deviceName,
                response.token
        );
        try {
            pairingStore.save(paired);
        } catch (IllegalStateException exception) {
            failPairing(operation, PairingError.STORAGE);
            return;
        }
        credentials = paired;
        authenticationRequired = false;
        pendingPairing = null;
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        clearManualPairingState();
        pairingExchangeInFlight = false;
        endpoint = server;
        listener.onPairingSucceeded(response.deviceName);
        connectSocket(false);
    }

    private void beginNextManualPairingVerification() {
        if (!active || pendingManualToken == null || pairingExchangeInFlight) return;
        for (DiscoveredServer candidate : candidates.values()) {
            if (candidate.transport != DiscoveredServer.Transport.LAN
                    || !attemptedManualServerIds.add(candidate.serverId)) {
                continue;
            }
            beginManualPairingVerification(candidate);
            return;
        }
        notifyStatus(Status.PAIRING, 0L);
    }

    private void beginManualPairingVerification(DiscoveredServer server) {
        String token = pendingManualToken;
        if (!active || token == null || pairingExchangeInFlight) return;
        int operation = operationGeneration;
        pairingExchangeInFlight = true;
        endpoint = server;
        notifyStatus(Status.VERIFYING, 0L);
        try {
            controlExecutor.execute(() -> {
                if (!active || operation != operationGeneration) return;
                try {
                    RemoteState verifiedState = apiClient.getState(server, token);
                    long receivedRealtimeMilliseconds = SystemClock.elapsedRealtime();
                    mainHandler.post(() -> finishManualPairing(
                            operation,
                            server,
                            token,
                            verifiedState,
                            receivedRealtimeMilliseconds
                    ));
                } catch (RemoteApiClient.HttpStatusException exception) {
                    boolean rejected = exception.statusCode == 401;
                    mainHandler.post(() -> continueManualPairing(operation, rejected));
                } catch (IOException exception) {
                    mainHandler.post(() -> continueManualPairing(operation, false));
                }
            });
        } catch (RejectedExecutionException exception) {
            continueManualPairing(operation, false);
        }
    }

    private void continueManualPairing(int operation, boolean rejectedToken) {
        if (!active || operation != operationGeneration || pendingManualToken == null) return;
        pairingExchangeInFlight = false;
        endpoint = null;
        manualPairingSawRejectedToken |= rejectedToken;
        beginNextManualPairingVerification();
    }

    private void finishManualPairing(
            int operation,
            DiscoveredServer server,
            String token,
            RemoteState verifiedState,
            long receivedRealtimeMilliseconds
    ) {
        if (!active || operation != operationGeneration
                || pendingManualToken == null || !pendingManualToken.equals(token)) {
            return;
        }
        PairingCredentials paired = new PairingCredentials(
                server.serverId,
                server.serviceName,
                server.serviceName,
                token
        );
        try {
            pairingStore.save(paired);
        } catch (IllegalStateException exception) {
            failPairing(operation, PairingError.STORAGE);
            return;
        }
        credentials = paired;
        authenticationRequired = false;
        pendingPairing = null;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        endpoint = server;
        listener.onPairingSucceeded(server.serviceName);
        listener.onStateChanged(verifiedState, receivedRealtimeMilliseconds);
        requestArtwork(verifiedState);
        connectSocket(false);
    }

    private void finishManualPairingTimeout() {
        if (!active || pendingManualToken == null) return;
        failPairing(
                operationGeneration,
                manualPairingSawRejectedToken
                        ? PairingError.TOKEN_REJECTED : PairingError.MANUAL_NETWORK
        );
    }

    private void finishQrPairingTimeout() {
        if (active && pendingPairing != null) failPairing(operationGeneration, PairingError.NETWORK);
    }

    private void failPairing(int operation, PairingError error) {
        if (!active || operation != operationGeneration) return;
        operationGeneration++;
        cancelTransportTimers();
        pendingPairing = null;
        mainHandler.removeCallbacks(qrPairingTimeoutRunnable);
        clearManualPairingState();
        pairingExchangeInFlight = false;
        endpoint = null;
        stopDirect();
        notifyStatus(Status.SEARCHING, 0L);
        listener.onPairingFailed(error);
        restartDiscovery();
        DiscoveredServer previous = credentials == null
                ? null : candidates.get(credentials.serverId);
        transportSelection.allowFallback();
        if (previous != null && !endpointRejected(previous)) {
            endpoint = previous;
            connectSocket(false);
        } else {
            scheduleDirectFallback(DIRECT_FALLBACK_DELAY_MILLISECONDS);
        }
    }

    private void clearManualPairingState() {
        pendingManualToken = null;
        attemptedManualServerIds.clear();
        manualPairingSawRejectedToken = false;
        mainHandler.removeCallbacks(manualPairingTimeoutRunnable);
    }

    private void connectSocket(boolean retry) {
        if (!active || credentials == null || endpoint == null) return;
        connectSocketTo(endpoint, retry);
    }

    private void connectSocketTo(DiscoveredServer selectedEndpoint, boolean retry) {
        if (!active || credentials == null || authenticationRequired) return;
        if (pendingEndpoint != null && pendingEndpoint.sameEndpoint(selectedEndpoint)) return;
        if (endpoint != null && !endpoint.sameEndpoint(selectedEndpoint)) {
            // A stale path's retry budget must not cancel a replacement transport's probe.
            reconnectFailures = 0;
            failureWindowStartedMs = 0L;
            mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
        }
        mainHandler.removeCallbacks(reconnectRunnable);
        cancelPendingSocket();
        final long attemptId = socketAttempts.begin();
        final DiscoveredServer connectingEndpoint = selectedEndpoint;
        pendingEndpoint = selectedEndpoint;
        attemptStartedMs = SystemClock.elapsedRealtime();
        connecting = true;
        record(ConnectionDiagnostics.Reason.ENDPOINT_FOUND, selectedEndpoint.transport,
                ConnectionDiagnostics.Stage.CONNECT, ConnectionDiagnostics.Result.STARTED);
        if (!connected) {
            notifyStatus(transportSelection.switching() ? Status.SWITCHING
                    : connectingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                    ? Status.DIRECT_CONNECTING : retry ? Status.RETRYING : Status.CONNECTING, 0L);
        }
        RemoteWebSocket socket = new RemoteWebSocket(
                connectingEndpoint,
                credentials.token,
                new RemoteWebSocket.Listener() {
                    @Override
                    public void onOpen() {
                        mainHandler.post(() -> {
                            if (socketAttempts.pending(attemptId)) {
                                record(ConnectionDiagnostics.Reason.ENDPOINT_FOUND,
                                        connectingEndpoint.transport,
                                        ConnectionDiagnostics.Stage.UPGRADE,
                                        ConnectionDiagnostics.Result.WAITING);
                            }
                        });
                    }

                    @Override
                    public void onState(RemoteState state, long receivedRealtimeMilliseconds) {
                        mainHandler.post(() -> handleSocketState(
                                attemptId,
                                state,
                                receivedRealtimeMilliseconds
                        ));
                    }

                    @Override
                    public void onFailure(RemoteWebSocket.FailureType type, Exception exception) {
                        mainHandler.post(() -> handleSocketFailure(
                                attemptId, connectingEndpoint, type
                        ));
                    }
                }
        );
        pendingSocket = socket;
        socket.start();
    }

    private void handleSocketState(
            long attemptId,
            RemoteState state,
            long receivedRealtimeMilliseconds
    ) {
        if (!active) return;
        if (socketAttempts.pending(attemptId) && pendingSocket != null) {
            DiscoveredServer confirmed = pendingEndpoint;
            if (confirmed.transport == DiscoveredServer.Transport.WIFI_DIRECT
                    && !transportSelection.allowsDirect()) return;
            RemoteWebSocket oldSocket = webSocket;
            webSocket = pendingSocket;
            pendingSocket = null;
            pendingEndpoint = null;
            socketAttempts.confirm(attemptId);
            endpoint = confirmed;
            connectionGeneration++;
            artworkGeneration++;
            artworkRequestKey = null;
            if (oldSocket != null) oldSocket.close();
            connectionLastRevision = -1L;
            connecting = false;
            connected = true;
            reconnectFailures = 0;
            failureWindowStartedMs = 0L;
            mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
            transportSelection.confirmed(endpoint.transport);
            mainHandler.removeCallbacks(selectionTimeoutRunnable);
            selectionDeadline.clear();
            if (endpoint != null && endpoint.transport == DiscoveredServer.Transport.LAN) {
                cancelDirectFallback();
                stopDirect();
            }
            record(ConnectionDiagnostics.Reason.SNAPSHOT, endpoint.transport,
                    ConnectionDiagnostics.Stage.SNAPSHOT, ConnectionDiagnostics.Result.SUCCEEDED);
            Log.i(TAG, "API v1 WebSocket connected over "
                    + (endpoint == null ? "unknown" : endpoint.transport));
            notifyStatus(endpoint != null
                            && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                            ? Status.CONNECTED_DIRECT : Status.CONNECTED,
                    0L);
        }
        if (!socketAttempts.confirmed(attemptId) || !connected
                || state.revision <= connectionLastRevision) return;
        connectionLastRevision = state.revision;
        listener.onStateChanged(state, receivedRealtimeMilliseconds);
        requestArtwork(state);
    }

    private void handleSocketFailure(
            long attemptId,
            DiscoveredServer failedEndpoint,
            RemoteWebSocket.FailureType type
    ) {
        if (!active || !socketAttempts.accepts(attemptId)) return;
        boolean probeFailed = socketAttempts.pending(attemptId);
        if (probeFailed) cancelPendingSocket();
        else {
            webSocket = null;
            socketAttempts.loseConfirmed();
            connected = false;
            connectionGeneration++;
        }
        record(type == RemoteWebSocket.FailureType.PROTOCOL
                        ? ConnectionDiagnostics.Reason.PROTOCOL_FAILURE
                        : ConnectionDiagnostics.Reason.SOCKET_FAILURE, failedEndpoint.transport,
                ConnectionDiagnostics.Stage.RECOVERY, ConnectionDiagnostics.Result.FAILED);
        if (connected) {
            rejectEndpoint(failedEndpoint);
            if (transportSelection.switching()) failManualSwitch(SwitchFailure.DIRECT_FAILED);
            else {
                transportSelection.allowFallback();
                if (failedEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) {
                    directSuppressed = true;
                    stopDirect();
                }
            }
            notifyStatus(confirmedStatus(), 0L);
            return;
        }
        if (type == RemoteWebSocket.FailureType.AUTHENTICATION) {
            Log.w(TAG, "WebSocket authentication rejected");
            invalidateRejectedCredentials(failedEndpoint);
            return;
        }
        if (failureWindowStartedMs == 0L) failureWindowStartedMs = SystemClock.elapsedRealtime();
        reconnectFailures++;
        if (!probeFailed) {
            transportSelection.unavailable(failedEndpoint.transport);
            beginPreferredDirectRecovery();
            // A known path loss never pays another initial LAN grace period.
            if (failedEndpoint.transport == DiscoveredServer.Transport.LAN) {
                scheduleDirectFallback(0L);
            } else {
                tryDirectCandidate();
                tryLanCandidate();
            }
        }
        if (TransportSelectionPolicy.exhausted(reconnectFailures,
                SystemClock.elapsedRealtime() - failureWindowStartedMs)
                && !retryingPreferredDirectApi(failedEndpoint)) {
            rejectEndpoint(failedEndpoint);
            mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
            boolean manualFailed = transportSelection.switching();
            if (manualFailed) failManualSwitch(SwitchFailure.DIRECT_FAILED);
            transportSelection.unavailable(failedEndpoint.transport);
            // An API-only Direct failure keeps its verified group and endpoint for the
            // bounded retry. Restarting Server must not force a new system negotiation.
            endpoint = null;
            restartDiscovery();
            tryDirectCandidate();
            tryLanCandidate();
            scheduleDirectFallback(0L);
            if (!connecting) notifyStatus(Status.SERVER_UNAVAILABLE, 0L);
            mainHandler.postDelayed(reconnectRunnable, TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS);
            return;
        }
        if (!connecting) endpoint = failedEndpoint;
        DiscoveredServer freshLan = candidates.get(targetServerId());
        if (freshLan != null && !freshLan.sameEndpoint(failedEndpoint)) tryLanCandidate();
        if (pendingSocket != null) return;
        if (reconnectFailures == 1) {
            mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
            mainHandler.postDelayed(pathFailureTimeoutRunnable,
                    TransportSelectionPolicy.PATH_FAILURE_WINDOW_MS);
        }
        long delay = ReconnectBackoff.delayMilliseconds(reconnectFailures - 1);
        Log.w(TAG, "WebSocket failed over " + failedEndpoint.transport
                + "; reconnect in " + delay + " ms; type=" + type);
        notifyStatus(Status.RETRYING, delay);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void invalidateRejectedCredentials(DiscoveredServer failedEndpoint) {
        record(ConnectionDiagnostics.Reason.AUTH_REJECTED, failedEndpoint.transport,
                ConnectionDiagnostics.Stage.UPGRADE, ConnectionDiagnostics.Result.FAILED);
        disconnectSocket();
        cancelTransportTimers();
        stopDirect();
        authenticationRequired = true;
        operationGeneration++;
        pendingPairing = null;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        notifyStatus(Status.AUTH_REQUIRED, 0L);
    }

    private void sendControl(String commandJson) {
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        int generation = connectionGeneration;
        if (!active || !connected || currentCredentials == null || currentEndpoint == null) {
            listener.onCommandError(false);
            return;
        }
        try {
            controlExecutor.execute(() -> {
                if (!requestStillCurrent(generation)) return;
                try {
                    apiClient.sendControl(currentEndpoint, currentCredentials.token, commandJson);
                } catch (RemoteApiClient.HttpStatusException exception) {
                    boolean authentication = exception.statusCode == 401;
                    mainHandler.post(() -> handleControlFailure(
                            generation, currentEndpoint, currentCredentials, authentication
                    ));
                } catch (IOException exception) {
                    mainHandler.post(() -> handleControlFailure(
                            generation, currentEndpoint, currentCredentials, false
                    ));
                }
            });
        } catch (RejectedExecutionException exception) {
            listener.onCommandError(false);
        }
    }

    private void handleControlFailure(
            int generation,
            DiscoveredServer failedEndpoint,
            PairingCredentials usedCredentials,
            boolean authentication
    ) {
        if (!requestStillCurrent(generation)
                || credentials != usedCredentials || endpoint != failedEndpoint) return;
        if (authentication) invalidateRejectedCredentials(failedEndpoint);
        listener.onCommandError(authentication);
    }

    private void requestArtwork(RemoteState state) {
        String stateKey = state.artworkKey();
        if (stateKey == null) {
            if (artworkRequestKey != null) {
                artworkRequestKey = null;
                artworkGeneration++;
                listener.onArtworkBytesChanged(null);
                listener.onArtworkChanged(null);
            }
            return;
        }
        PairingCredentials currentCredentials = credentials;
        DiscoveredServer currentEndpoint = endpoint;
        if (currentCredentials == null || currentEndpoint == null) return;
        String requestKey = currentEndpoint.addressLabel() + '\u0000' + stateKey;
        if (requestKey.equals(artworkRequestKey)) return;
        listener.onArtworkBytesChanged(null);
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
                    byte[] sessionArtwork = RemoteSessionArtwork.encode(bitmap);
                    mainHandler.post(() -> {
                        if (active && generation == artworkGeneration
                                && requestKey.equals(artworkRequestKey)) {
                            listener.onArtworkBytesChanged(sessionArtwork);
                            listener.onArtworkChanged(bitmap);
                        } else {
                            bitmap.recycle();
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
        if (!active || targetServerId() == null || !transportSelection.allowsDirect()
                || directNeedsUserRetry || authenticationRequired) return;
        Log.i(TAG, "Direct fallback state=" + directState + ", reason=" + reason);
        SwitchFailure failure = null;
        switch (directState) {
            case PERMISSION_REQUIRED: failure = SwitchFailure.PERMISSION; break;
            case LOCATION_DISABLED: failure = SwitchFailure.LOCATION; break;
            case WIFI_DISABLED: failure = SwitchFailure.WIFI; break;
            case UNSUPPORTED: failure = SwitchFailure.UNSUPPORTED; break;
            case PHONE_GROUP_OWNER:
            case FAILED: failure = SwitchFailure.DIRECT_FAILED; break;
            default: break;
        }
        // A retained group can be lost during the fallback cooldown. Do not swallow a failed
        // recovery negotiation then: it must still require user retry, not another approval loop.
        if (failure == null && directSuppressed) return;
        if (failure != null) {
            if (transportSelection.switching()) failManualSwitch(failure);
            else {
                transportSelection.allowFallback();
                directSuppressed = true;
                directNeedsUserRetry = true;
                stopDirect();
                mainHandler.removeCallbacks(selectionTimeoutRunnable);
                selectionDeadline.clear();
                tryLanCandidate();
            }
        }
        if (connected || connecting) {
            if (connected) notifyStatus(confirmedStatus(), 0L);
            return;
        }
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
                notifyStatus(Status.DIRECT_CONNECTING, 0L);
                break;
            case WAITING_FOR_APPROVAL:
                notifyStatus(Status.DIRECT_WAITING_APPROVAL, 0L);
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
        String targetServerId = targetServerId();
        if (!active || targetServerId == null || !targetServerId.equals(server.serverId)
                || !transportSelection.allowsDirect() || directNeedsUserRetry
                || authenticationRequired || !directClient.isActive()) {
            return;
        }
        directEndpoint = server;
        if (pendingPairing != null) {
            if (!directSuppressed) beginPairingExchange(server);
            return;
        }
        if (credentials == null) return;
        beginPreferredDirectRecovery();
        if (directSuppressed) return;
        if (pendingEndpoint != null && pendingEndpoint.transport == DiscoveredServer.Transport.LAN
                && transportSelection.preferred() == DiscoveredServer.Transport.LAN) return;
        if (!transportSelection.maySelect(server.transport,
                endpoint == null ? null : endpoint.transport, connected)) {
            stopDirect();
            return;
        }
        Log.i(TAG, "Selecting verified Wi-Fi Direct endpoint");
        tryDirectCandidate();
    }

    @Override
    public void onDirectDisconnected() {
        if (!active || !transportSelection.allowsDirect()) return;
        directEndpoint = null;
        if (pendingEndpoint != null
                && pendingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) {
            cancelPendingSocket();
        }
        if (endpoint == null || endpoint.transport != DiscoveredServer.Transport.WIFI_DIRECT) return;
        transportSelection.unavailable(DiscoveredServer.Transport.WIFI_DIRECT);
        Log.w(TAG, "Wi-Fi Direct endpoint lost; restarting LAN and direct discovery");
        // Group loss must not cancel an unrelated LAN replacement already being confirmed.
        disconnectConfirmedSocket();
        endpoint = null;
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
        beginPreferredDirectRecovery();
        tryLanCandidate();
        scheduleDirectFallback(0L);
    }

    private void scheduleDirectFallback(long delayMilliseconds) {
        if (active && targetServerId() != null && transportSelection.allowsDirect()
                && !directSuppressed && !authenticationRequired
                && (!connected || transportSelection.switching()
                    || transportSelection.policy() == ConnectionPolicy.DIRECT_PREFERRED)
                && directFallbackDeadline.schedule(SystemClock.elapsedRealtime(), delayMilliseconds)) {
            mainHandler.removeCallbacks(directFallbackRunnable);
            mainHandler.postDelayed(directFallbackRunnable, Math.max(0L, delayMilliseconds));
        }
    }

    private void startDirectFallback() {
        directFallbackDeadline.clear();
        String targetServerId = targetServerId();
        if (!active || targetServerId == null || !transportSelection.allowsDirect()
                || directSuppressed || authenticationRequired) return;
        if (directClient.isActive()) {
            tryDirectCandidate();
            return;
        }
        if (connected && !transportSelection.maySelect(DiscoveredServer.Transport.WIFI_DIRECT,
                endpoint.transport, true)) return;
        if (!transportSelection.switching()
                && transportSelection.preferred() == DiscoveredServer.Transport.LAN) {
            transportSelection.allowFallback();
        }
        if (directEndpoint != null) {
            tryDirectCandidate();
            return;
        }
        Log.i(TAG, "LAN grace period expired; starting Wi-Fi Direct fallback");
        scheduleSelectionTimeout();
        directClient.start(targetServerId, targetServiceName());
    }

    private void startPreferredDirectWindow() {
        if (!transportSelection.allowsDirect()) return;
        transportSelection.beginRecovery(SystemClock.elapsedRealtime());
        scheduleSelectionTimeout();
        scheduleDirectFallback(0L);
    }

    private void beginPreferredDirectRecovery() {
        if (!active || connected || connecting || authenticationRequired || isPairingInProgress()
                || directNeedsUserRetry
                || transportSelection.policy() != ConnectionPolicy.DIRECT_PREFERRED
                || !transportSelection.beginRecovery(SystemClock.elapsedRealtime())) return;
        directSuppressed = false;
        reconnectFailures = 0;
        failureWindowStartedMs = 0L;
        mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
        mainHandler.removeCallbacks(selectionTimeoutRunnable);
        selectionDeadline.clear();
        if (directEndpoint != null) rejectedEndpoints.remove(directEndpoint);
        record(ConnectionDiagnostics.Reason.RECOVERY_DIRECT_PRIORITY,
                DiscoveredServer.Transport.WIFI_DIRECT, ConnectionDiagnostics.Stage.RECOVERY,
                ConnectionDiagnostics.Result.STARTED);
        record(hasUsableDirectGroup() ? ConnectionDiagnostics.Reason.RECOVERY_REUSE_GROUP
                        : ConnectionDiagnostics.Reason.RECOVERY_DISCOVER_DIRECT,
                DiscoveredServer.Transport.WIFI_DIRECT, ConnectionDiagnostics.Stage.RECOVERY,
                ConnectionDiagnostics.Result.STARTED);
        scheduleSelectionTimeout();
        scheduleDirectFallback(0L);
    }

    private boolean hasUsableDirectGroup() {
        return directEndpoint != null && directClient.isActive()
                && directClient.isGroupAddress(directEndpoint.address);
    }

    private boolean retryingPreferredDirectApi(DiscoveredServer failed) {
        return failed.transport == DiscoveredServer.Transport.WIFI_DIRECT
                && transportSelection.shouldRetryDirectApi(
                        hasUsableDirectGroup(), SystemClock.elapsedRealtime());
    }

    private void scheduleSelectionTimeout() {
        if (!selectionDeadline.schedule(SystemClock.elapsedRealtime(),
                TransportSelectionPolicy.SELECTION_TIMEOUT_MS)) return;
        mainHandler.removeCallbacks(selectionTimeoutRunnable);
        mainHandler.postDelayed(selectionTimeoutRunnable, TransportSelectionPolicy.SELECTION_TIMEOUT_MS);
    }

    private void handlePathFailureTimeout() {
        if (!active || connected || authenticationRequired) return;
        DiscoveredServer failed = pendingEndpoint != null ? pendingEndpoint : endpoint;
        if (failed == null) return;
        if (retryingPreferredDirectApi(failed)) return;
        rejectEndpoint(failed);
        cancelPendingSocket();
        endpoint = null;
        if (transportSelection.switching()) failManualSwitch(SwitchFailure.TIMEOUT);
        transportSelection.unavailable(failed.transport);
        tryDirectCandidate();
        tryLanCandidate();
        scheduleDirectFallback(0L);
        if (!connecting) notifyStatus(Status.SERVER_UNAVAILABLE, 0L);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS);
    }

    private void handleSelectionTimeout() {
        selectionDeadline.clear();
        if (!active) return;
        if (transportSelection.switching()) {
            failManualSwitch(SwitchFailure.TIMEOUT);
            return;
        }
        if (connected && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) return;
        transportSelection.allowFallback();
        directSuppressed = true;
        if (pendingEndpoint != null
                && pendingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) cancelPendingSocket();
        // Retain a known group while disconnected. A later API retry needs no Android approval;
        // only a confirmed LAN replacement releases it. Cancel unfinished negotiation as before.
        if (!hasUsableDirectGroup()) stopDirect();
        if (!connected) endpoint = null;
        record(ConnectionDiagnostics.Reason.RECOVERY_LAN_FALLBACK,
                DiscoveredServer.Transport.LAN, ConnectionDiagnostics.Stage.RECOVERY,
                ConnectionDiagnostics.Result.STARTED);
        tryLanCandidate();
        if (!connected && !connecting) {
            notifyStatus(Status.SERVER_UNAVAILABLE, 0L);
            mainHandler.removeCallbacks(reconnectRunnable);
            mainHandler.postDelayed(reconnectRunnable, TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS);
        }
    }

    private void failManualSwitch(SwitchFailure failure) {
        DiscoveredServer.Transport requested = transportSelection.preferred();
        transportSelection.unavailable(requested);
        switchFailure = failure;
        mainHandler.removeCallbacks(selectionTimeoutRunnable);
        selectionDeadline.clear();
        cancelPendingSocket();
        if (requested == DiscoveredServer.Transport.WIFI_DIRECT) {
            directSuppressed = true;
            directNeedsUserRetry = true;
            stopDirect();
        }
        record(ConnectionDiagnostics.Reason.SWITCH_TIMEOUT, requested,
                ConnectionDiagnostics.Stage.RECOVERY, ConnectionDiagnostics.Result.FAILED);
        if (connected) notifyStatus(confirmedStatus(), 0L);
        else {
            tryLanCandidate();
            scheduleDirectFallback(0L);
            if (!connecting) notifyStatus(Status.SERVER_UNAVAILABLE, 0L);
        }
    }

    private void tryLanCandidate() {
        if (!active || authenticationRequired || !hasLanCapableNetwork()) return;
        DiscoveredServer candidate = candidates.get(targetServerId());
        if (candidate == null || isDirectAddress(candidate) || endpointRejected(candidate)
                || !transportSelection.maySelect(DiscoveredServer.Transport.LAN,
                        endpoint == null ? null : endpoint.transport, connected)) return;
        if (connected && endpoint.sameEndpoint(candidate)) return;
        record(ConnectionDiagnostics.Reason.RECOVERY_SELECT_LAN,
                DiscoveredServer.Transport.LAN, ConnectionDiagnostics.Stage.CONNECT,
                ConnectionDiagnostics.Result.STARTED);
        if (pendingPairing != null) beginPairingExchange(candidate);
        else connectSocketTo(candidate, false);
    }

    private void tryDirectCandidate() {
        if (directEndpoint == null || directSuppressed || !directClient.isActive()
                || endpointRejected(directEndpoint) && !retryingPreferredDirectApi(directEndpoint)
                || pendingEndpoint != null
                    && pendingEndpoint.transport == DiscoveredServer.Transport.LAN
                    && transportSelection.preferred() == DiscoveredServer.Transport.LAN
                || !transportSelection.maySelect(DiscoveredServer.Transport.WIFI_DIRECT,
                        endpoint == null ? null : endpoint.transport, connected)) return;
        record(ConnectionDiagnostics.Reason.RECOVERY_SELECT_DIRECT,
                DiscoveredServer.Transport.WIFI_DIRECT, ConnectionDiagnostics.Stage.CONNECT,
                ConnectionDiagnostics.Result.STARTED);
        connectSocketTo(directEndpoint, false);
    }

    private boolean isDirectAddress(DiscoveredServer server) {
        return directClient.isGroupAddress(server.address)
                || endpoint != null && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                && endpoint.address.equals(server.address)
                || pendingEndpoint != null
                && pendingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                && pendingEndpoint.address.equals(server.address);
    }

    private void stopDirect() {
        directEndpoint = null;
        directClient.stop();
    }

    private boolean endpointRejected(DiscoveredServer server) {
        Long until = rejectedEndpoints.get(server);
        return until != null && SystemClock.elapsedRealtime() < until;
    }

    private void rejectEndpoint(DiscoveredServer server) {
        if (rejectedEndpoints.size() >= 16) rejectedEndpoints.clear();
        rejectedEndpoints.put(server, SystemClock.elapsedRealtime()
                + TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS);
    }

    private Status confirmedStatus() {
        return endpoint != null && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                ? Status.CONNECTED_DIRECT : Status.CONNECTED;
    }

    private void cancelDirectFallback() {
        directFallbackDeadline.clear();
        mainHandler.removeCallbacks(directFallbackRunnable);
    }

    private void cancelTransportTimers() {
        cancelDirectFallback();
        networkRecoveryDeadline.clear();
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(selectionTimeoutRunnable);
        selectionDeadline.clear();
        mainHandler.removeCallbacks(pathFailureTimeoutRunnable);
    }

    private void resetTransportChoice() {
        cancelTransportTimers();
        transportSelection.reset();
        directSuppressed = false;
        directNeedsUserRetry = false;
        authenticationRequired = false;
        switchFailure = SwitchFailure.NONE;
        rejectedEndpoints.clear();
        directEndpoint = null;
        reconnectFailures = 0;
        failureWindowStartedMs = 0L;
    }

    private void startNetworkMonitor() {
        if (networkMonitorRegistered || connectivityManager == null) return;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities caps = getNetworkCapabilities(network);
                lanNetworkTracker.update(network,
                        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P));
            }
            // Wi-Fi Direct can also report TRANSPORT_WIFI, so callback capabilities are
            // classified separately instead of treating every matching network as LAN.
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            networkCallback = networkCallback(++networkMonitorGeneration);
            connectivityManager.registerNetworkCallback(request, networkCallback, mainHandler);
            networkMonitorRegistered = true;
        } catch (RuntimeException ignored) {
            networkMonitorRegistered = false;
        }
    }

    private void stopNetworkMonitor() {
        networkMonitorGeneration++;
        lanNetworkTracker.clear();
        if (!networkMonitorRegistered || connectivityManager == null) return;
        networkMonitorRegistered = false;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // The platform may already have released the callback.
        }
    }

    private void scheduleNetworkRecovery(String reason, long delayMilliseconds) {
        Log.d(TAG, "Scheduling transport recovery: " + reason);
        if (active && networkRecoveryDeadline.schedule(SystemClock.elapsedRealtime(), delayMilliseconds)) {
            mainHandler.removeCallbacks(networkRecoveryRunnable);
            mainHandler.postDelayed(
                    networkRecoveryRunnable,
                    Math.max(0L, delayMilliseconds)
            );
        }
    }

    private void handleNetworkRecovery() {
        networkRecoveryDeadline.clear();
        if (!active) return;
        lanNetworkAvailable = hasLanCapableNetwork();
        Log.i(TAG, "Transport recovery evaluation: lanNetworkAvailable="
                + lanNetworkAvailable + ", endpoint="
                + (endpoint == null ? "none" : endpoint.transport));
        boolean lostLan = endpoint != null && TransportRecoveryPolicy.shouldInvalidateEndpoint(
                endpoint.transport,
                lanNetworkAvailable && (endpoint.network == null
                        || isInfrastructureLan(getNetworkCapabilities(endpoint.network)))
        );
        if (pendingEndpoint != null
                && pendingEndpoint.transport == DiscoveredServer.Transport.LAN
                && (!lanNetworkAvailable || pendingEndpoint.network != null
                && !isInfrastructureLan(getNetworkCapabilities(pendingEndpoint.network)))) {
            cancelPendingSocket();
            transportSelection.unavailable(DiscoveredServer.Transport.LAN);
            if (!connected) notifyStatus(transportSelection.allowsDirect()
                    ? Status.SEARCHING : Status.SERVER_UNAVAILABLE, 0L);
        }
        if (lostLan) {
            Log.w(TAG, "LAN route disappeared; invalidating endpoint and resetting P2P fallback");
            candidates.values().removeIf(
                    server -> server.transport == DiscoveredServer.Transport.LAN
            );
            endpoint = null;
            disconnectConfirmedSocket();
            transportSelection.unavailable(DiscoveredServer.Transport.LAN);
            beginPreferredDirectRecovery();
            if (!connecting) notifyStatus(transportSelection.allowsDirect()
                    ? Status.SEARCHING : Status.SERVER_UNAVAILABLE, 0L);
        }
        restartDiscovery();
        if (connected) return;
        if (targetServerId() != null && !connected) {
            scheduleDirectFallback(TransportRecoveryPolicy.directFallbackDelayMilliseconds(
                    lanNetworkAvailable && !lostLan && failureWindowStartedMs == 0L,
                    DIRECT_FALLBACK_DELAY_MILLISECONDS
            ));
        }
    }

    private boolean hasLanCapableNetwork() {
        if (connectivityManager == null) return false;
        if (networkMonitorRegistered) return lanNetworkTracker.hasLanNetwork();
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = activeNetwork == null
                    ? null : connectivityManager.getNetworkCapabilities(activeNetwork);
            return isInfrastructureLan(capabilities);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to evaluate LAN transport");
            return false;
        }
    }

    private NetworkCapabilities getNetworkCapabilities(Network network) {
        if (connectivityManager == null) return null;
        try {
            return connectivityManager.getNetworkCapabilities(network);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read network capabilities");
            return null;
        }
    }

    private void updateTrackedNetwork(
            Network network,
            NetworkCapabilities capabilities,
            String reason
    ) {
        boolean hadLanNetwork = lanNetworkTracker.hasLanNetwork();
        boolean membershipChanged = lanNetworkTracker.update(
                network,
                capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)
        );
        if (!membershipChanged) return;
        boolean hasLanNetwork = lanNetworkTracker.hasLanNetwork();
        scheduleNetworkRecovery(
                reason + "; infrastructureLan=" + hasLanNetwork,
                TransportRecoveryPolicy.networkRecoveryDelayMilliseconds(
                        hadLanNetwork,
                        hasLanNetwork,
                        NETWORK_RECOVERY_DEBOUNCE_MILLISECONDS
                )
        );
    }

    private static boolean isInfrastructureLan(NetworkCapabilities capabilities) {
        return capabilities != null && TransportRecoveryPolicy.isInfrastructureLan(
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)
        );
    }

    private void runReconnect() {
        if (!active || connected || connecting || authenticationRequired) return;
        beginPreferredDirectRecovery();
        if (endpoint != null && endpoint.transport == DiscoveredServer.Transport.LAN
                && !hasLanCapableNetwork()) {
            Log.w(TAG, "Skipping stale LAN reconnect; running transport recovery immediately");
            handleNetworkRecovery();
            return;
        }
        if (active && credentials != null && pendingPairing == null
                && pendingManualToken == null && endpoint != null && !endpointRejected(endpoint)
                && (endpoint.transport != DiscoveredServer.Transport.WIFI_DIRECT || !directSuppressed)
                && transportSelection.maySelect(endpoint.transport, null, false)) {
            Log.i(TAG, "Running scheduled WebSocket reconnect over " + endpoint.transport);
            connectSocket(true);
        } else if (targetServerId() != null) {
            // Cooldown expiry can retry discovery, but never an ambiguous mutation.
            restartDiscovery();
            tryDirectCandidate();
            tryLanCandidate();
            if (!connecting && !directSuppressed) scheduleDirectFallback(0L);
            if (!connecting) mainHandler.postDelayed(reconnectRunnable,
                    TransportSelectionPolicy.ENDPOINT_COOLDOWN_MS);
        }
    }

    private void restartDiscovery() {
        if (!active) return;
        discoveryClient.stop();
        discoveryClient.start();
    }

    private String targetServerId() {
        if (pendingManualToken != null) return null;
        return pendingPairing != null
                ? pendingPairing.serverId
                : credentials == null ? null : credentials.serverId;
    }

    private String targetServiceName() {
        return pendingPairing != null
                ? pendingPairing.deviceName
                : credentials == null ? "Poweramp Remote Server" : credentials.serviceName;
    }

    private void disconnectSocket() {
        cancelPendingSocket();
        disconnectConfirmedSocket();
    }

    private void disconnectConfirmedSocket() {
        connectionGeneration++;
        connected = false;
        RemoteWebSocket current = webSocket;
        webSocket = null;
        socketAttempts.loseConfirmed();
        artworkGeneration++;
        artworkRequestKey = null;
        if (current != null) current.close();
    }

    private void cancelPendingSocket() {
        RemoteWebSocket previous = pendingSocket;
        pendingSocket = null;
        pendingEndpoint = null;
        socketAttempts.cancelPending();
        connecting = false;
        if (previous != null) previous.close();
    }

    private void record(ConnectionDiagnostics.Reason reason,
            DiscoveredServer.Transport transport, ConnectionDiagnostics.Stage stage,
            ConnectionDiagnostics.Result result) {
        ConnectionDiagnostics.record(reason, transport, stage,
                stage == ConnectionDiagnostics.Stage.DISCOVERY ? 0L
                        : attemptStartedMs == 0L ? 0L
                        : SystemClock.elapsedRealtime() - attemptStartedMs, result);
    }

    private void notifyStatus(Status status, long retryDelayMilliseconds) {
        record(status == Status.RETRYING ? ConnectionDiagnostics.Reason.RETRY
                        : ConnectionDiagnostics.Reason.NETWORK_CHANGED,
                endpoint == null ? null : endpoint.transport,
                ConnectionDiagnostics.Stage.RUNTIME,
                status == Status.CONNECTED || status == Status.CONNECTED_DIRECT
                        ? ConnectionDiagnostics.Result.SUCCEEDED : ConnectionDiagnostics.Result.WAITING);
        listener.onStatusChanged(status, retryDelayMilliseconds);
    }

    private boolean requestStillCurrent(int generation) {
        return active && generation == connectionGeneration;
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
        libraryExecutor.shutdownNow();
        libraryArtworkExecutor.shutdownNow();
        libraryArtworkRequests.clear();
        representativeArtworkRequests.clear();
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Client runtime closed");
    }
}
