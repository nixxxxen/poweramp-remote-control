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
        CONNECTED_DIRECT,
        DIRECT_UNSUPPORTED,
        DIRECT_ACTION_REQUIRED,
        RETRYING,
        AUTH_REQUIRED,
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
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
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
                    updateTrackedNetwork(
                            network,
                            networkCapabilities,
                            "network capabilities changed"
                    );
                }

                @Override
                public void onLost(Network network) {
                    boolean hadLanNetwork = lanNetworkTracker.hasLanNetwork();
                    if (lanNetworkTracker.remove(network)) {
                        scheduleNetworkRecovery(
                                "infrastructure LAN network lost",
                                TransportRecoveryPolicy.networkRecoveryDelayMilliseconds(
                                        hadLanNetwork,
                                        lanNetworkTracker.hasLanNetwork(),
                                        NETWORK_RECOVERY_DEBOUNCE_MILLISECONDS
                                )
                        );
                    }
                }

            };

    private PairingCredentials credentials;
    private DiscoveredServer endpoint;
    private PairingQrPayload pendingPairing;
    private String pendingManualToken;
    private final Set<String> attemptedManualServerIds = new HashSet<>();
    private RemoteWebSocket webSocket;
    private boolean active;
    private boolean closed;
    private boolean connecting;
    private boolean connected;
    private boolean pairingExchangeInFlight;
    private boolean manualPairingSawRejectedToken;
    private int connectionGeneration;
    private int operationGeneration;
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
        Log.i(TAG, "Client runtime started; LAN discovery has priority");
        notifyStatus(Status.SEARCHING, 0L);
        startNetworkMonitor();
        discoveryClient.start();
        scheduleDirectFallback(DIRECT_FALLBACK_DELAY_MILLISECONDS);
    }

    void stop() {
        if (!active) return;
        Log.i(TAG, "Client runtime explicitly stopping");
        active = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(discoveryRestartRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        mainHandler.removeCallbacks(manualPairingTimeoutRunnable);
        stopNetworkMonitor();
        discoveryClient.stop();
        directClient.stop();
        candidates.clear();
        pendingPairing = null;
        pendingManualToken = null;
        attemptedManualServerIds.clear();
        pairingExchangeInFlight = false;
        endpoint = null;
        disconnectSocket();
    }

    @Override
    public void pairQr(PairingQrPayload payload) {
        if (!active || payload == null) {
            listener.onPairingFailed(PairingError.INVALID_QR);
            return;
        }
        operationGeneration++;
        pendingPairing = payload;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        disconnectSocket();
        directClient.stop();
        endpoint = null;
        notifyStatus(Status.PAIRING, 0L);
        DiscoveredServer candidate = candidates.get(payload.serverId);
        if (candidate != null) {
            beginPairingExchange(candidate);
        } else {
            restartDiscovery();
            scheduleDirectFallback(DIRECT_FALLBACK_DELAY_MILLISECONDS);
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
        pendingPairing = null;
        pendingManualToken = token;
        attemptedManualServerIds.clear();
        manualPairingSawRejectedToken = false;
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(directFallbackRunnable);
        mainHandler.removeCallbacks(manualPairingTimeoutRunnable);
        disconnectSocket();
        directClient.stop();
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
        pendingPairing = null;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        mainHandler.removeCallbacks(directFallbackRunnable);
        directClient.stop();
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
        if (!active || targetServerId() == null) return;
        restartDiscovery();
        if (directClient.isActive()) {
            directClient.retry();
        } else {
            directClient.start(targetServerId(), targetServiceName());
        }
    }

    void retryLanDiscovery() {
        if (!active) return;
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
    }

    void onDirectPermissionOrSettingsChanged() {
        if (!active || targetServerId() == null) return;
        if (directClient.isActive()) {
            directClient.onPermissionOrSettingsChanged();
        } else {
            scheduleDirectFallback(0L);
        }
    }

    @Override
    public void onServerFound(DiscoveredServer server) {
        if (!active) return;
        Log.i(TAG, "LAN NSD Server found; transport=" + server.transport);
        candidates.put(server.serverId, server);
        if (pendingPairing != null) {
            if (pendingPairing.serverId.equals(server.serverId)) {
                mainHandler.removeCallbacks(directFallbackRunnable);
                directClient.stop();
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
        Log.i(TAG, "Selecting LAN endpoint and stopping direct fallback/group");
        directClient.stop();
        endpoint = server;
        connectSocket(false);
    }

    @Override
    public void onServerLost(String serviceName) {
        if (!active) return;
        Log.i(TAG, "LAN NSD service lost");
        candidates.values().removeIf(server -> serviceName.equals(server.serviceName));
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
        Log.w(TAG, "LAN NSD discovery failed: " + errorCode);
        notifyStatus(Status.SEARCHING, ReconnectBackoff.delayMilliseconds(1));
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
        pendingPairing = null;
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

    private void failPairing(int operation, PairingError error) {
        if (!active || operation != operationGeneration) return;
        operationGeneration++;
        pendingPairing = null;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        endpoint = null;
        directClient.stop();
        notifyStatus(Status.SEARCHING, 0L);
        listener.onPairingFailed(error);
        restartDiscovery();
        DiscoveredServer previous = credentials == null
                ? null : candidates.get(credentials.serverId);
        if (previous != null) {
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
        mainHandler.removeCallbacks(reconnectRunnable);
        disconnectSocket();
        final int generation = ++connectionGeneration;
        final DiscoveredServer connectingEndpoint = endpoint;
        connectionLastRevision = -1L;
        connecting = true;
        connected = false;
        if (connectingEndpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT) {
            Log.i(TAG, "Opening API v1 WebSocket over Wi-Fi Direct");
            notifyStatus(Status.DIRECT_CONNECTING, 0L);
        } else {
            Log.i(TAG, "Opening API v1 WebSocket over LAN; retry=" + retry);
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
                    public void onState(RemoteState state, long receivedRealtimeMilliseconds) {
                        mainHandler.post(() -> handleSocketState(
                                generation,
                                state,
                                receivedRealtimeMilliseconds
                        ));
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

    private void handleSocketState(
            int generation,
            RemoteState state,
            long receivedRealtimeMilliseconds
    ) {
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
            Log.i(TAG, "API v1 WebSocket connected over "
                    + (endpoint == null ? "unknown" : endpoint.transport));
            notifyStatus(endpoint != null
                            && endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                            ? Status.CONNECTED_DIRECT : Status.CONNECTED,
                    0L);
        }
        listener.onStateChanged(state, receivedRealtimeMilliseconds);
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
            Log.w(TAG, "WebSocket authentication rejected");
            invalidateRejectedCredentials(failedEndpoint);
            return;
        }
        long delay = ReconnectBackoff.delayMilliseconds(reconnectFailures++);
        Log.w(TAG, "WebSocket failed over " + failedEndpoint.transport
                + "; reconnect in " + delay + " ms; type=" + type);
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
        pendingPairing = null;
        clearManualPairingState();
        pairingExchangeInFlight = false;
        notifyStatus(Status.AUTH_REQUIRED, 0L);
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
        if (!active || targetServerId() == null || connected) return;
        Log.i(TAG, "Direct fallback state=" + directState + ", reason=" + reason);
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
        String targetServerId = targetServerId();
        if (!active || targetServerId == null || !targetServerId.equals(server.serverId)) {
            return;
        }
        if (pendingPairing != null) {
            beginPairingExchange(server);
            return;
        }
        if (credentials == null) return;
        if (endpoint != null
                && endpoint.transport == DiscoveredServer.Transport.LAN
                && (connecting || connected)) {
            directClient.stop();
            return;
        }
        endpoint = server;
        Log.i(TAG, "Selecting verified Wi-Fi Direct endpoint");
        connectSocket(false);
    }

    @Override
    public void onDirectDisconnected() {
        if (!active || endpoint == null
                || endpoint.transport != DiscoveredServer.Transport.WIFI_DIRECT) {
            return;
        }
        endpoint = null;
        Log.w(TAG, "Wi-Fi Direct endpoint lost; restarting LAN and direct discovery");
        disconnectSocket();
        notifyStatus(Status.SEARCHING, 0L);
        restartDiscovery();
        scheduleDirectFallback(0L);
    }

    private void scheduleDirectFallback(long delayMilliseconds) {
        mainHandler.removeCallbacks(directFallbackRunnable);
        if (active && targetServerId() != null && !connected) {
            mainHandler.postDelayed(directFallbackRunnable, Math.max(0L, delayMilliseconds));
        }
    }

    private void startDirectFallback() {
        String targetServerId = targetServerId();
        if (!active || targetServerId == null || connected || directClient.isActive()) return;
        Log.i(TAG, "LAN grace period expired; starting Wi-Fi Direct fallback");
        directClient.start(targetServerId, targetServiceName());
    }

    private void startNetworkMonitor() {
        if (networkMonitorRegistered || connectivityManager == null) return;
        try {
            // Wi-Fi Direct can also report TRANSPORT_WIFI, so callback capabilities are
            // classified separately instead of treating every matching network as LAN.
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback, mainHandler);
            networkMonitorRegistered = true;
        } catch (RuntimeException ignored) {
            networkMonitorRegistered = false;
        }
    }

    private void stopNetworkMonitor() {
        if (!networkMonitorRegistered || connectivityManager == null) return;
        networkMonitorRegistered = false;
        lanNetworkTracker.clear();
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // The platform may already have released the callback.
        }
    }

    private void scheduleNetworkRecovery(String reason, long delayMilliseconds) {
        Log.d(TAG, "Scheduling transport recovery: " + reason);
        mainHandler.removeCallbacks(networkRecoveryRunnable);
        if (active) {
            mainHandler.postDelayed(
                    networkRecoveryRunnable,
                    Math.max(0L, delayMilliseconds)
            );
        }
    }

    private void handleNetworkRecovery() {
        if (!active) return;
        lanNetworkAvailable = hasLanCapableNetwork();
        Log.i(TAG, "Transport recovery evaluation: lanNetworkAvailable="
                + lanNetworkAvailable + ", endpoint="
                + (endpoint == null ? "none" : endpoint.transport));
        if (endpoint != null && TransportRecoveryPolicy.shouldInvalidateEndpoint(
                endpoint.transport,
                lanNetworkAvailable
        )) {
            Log.w(TAG, "LAN route disappeared; invalidating endpoint and resetting P2P fallback");
            candidates.values().removeIf(
                    server -> server.transport == DiscoveredServer.Transport.LAN
            );
            endpoint = null;
            disconnectSocket();
            notifyStatus(Status.SEARCHING, 0L);
        }
        restartDiscovery();
        if (targetServerId() != null && !connected) {
            if (!lanNetworkAvailable && directClient.isActive()) {
                directClient.reinitializeDiscovery("LAN route transition");
            }
            scheduleDirectFallback(TransportRecoveryPolicy.directFallbackDelayMilliseconds(
                    lanNetworkAvailable,
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
            Log.w(TAG, "Unable to evaluate LAN transport", exception);
            return false;
        }
    }

    private NetworkCapabilities getNetworkCapabilities(Network network) {
        if (connectivityManager == null) return null;
        try {
            return connectivityManager.getNetworkCapabilities(network);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read network capabilities", exception);
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
        if (endpoint != null && endpoint.transport == DiscoveredServer.Transport.LAN
                && !hasLanCapableNetwork()) {
            Log.w(TAG, "Skipping stale LAN reconnect; running transport recovery immediately");
            handleNetworkRecovery();
            return;
        }
        if (active && credentials != null && pendingPairing == null
                && pendingManualToken == null && endpoint != null) {
            Log.i(TAG, "Running scheduled WebSocket reconnect over " + endpoint.transport);
            connectSocket(true);
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
        libraryExecutor.shutdownNow();
        libraryArtworkExecutor.shutdownNow();
        libraryArtworkRequests.clear();
        representativeArtworkRequests.clear();
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Client runtime closed");
    }
}
