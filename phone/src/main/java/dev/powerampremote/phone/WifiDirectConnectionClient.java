package dev.powerampremote.phone;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.util.Map;

/** Discovers and joins one previously paired Server through Wi-Fi Direct DNS-SD. */
@SuppressWarnings("deprecation")
final class WifiDirectConnectionClient implements AutoCloseable {
    private static final String TAG = "PhoneWifiDirect";
    private static final long DISCOVERY_REFRESH_MILLISECONDS = 15_000L;
    private static final long DISCOVERY_ACTION_TIMEOUT_MILLISECONDS = 30_000L;
    private static final long CONNECTION_TIMEOUT_MILLISECONDS = 30_000L;
    private static final int LEGACY_GROUP_OWNER_INTENT_MIN = 0;

    enum State {
        PERMISSION_REQUIRED,
        LOCATION_DISABLED,
        WIFI_DISABLED,
        DISCOVERING,
        CONNECTING,
        WAITING_FOR_APPROVAL,
        UNSUPPORTED,
        FAILED,
        PHONE_GROUP_OWNER
    }

    interface Listener {
        void onDirectStatus(State state, int reason);

        void onDirectEndpoint(DiscoveredServer server);

        void onDirectDisconnected();
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private final WifiManager wifiManager;
    private final boolean featureSupported;
    private final IntentFilter intentFilter = new IntentFilter();
    private final WifiDirectRecoveryPolicy recoveryPolicy = new WifiDirectRecoveryPolicy();
    private final Runnable discoveryRefresh = this::refreshDiscovery;
    private final Runnable discoveryRetry = this::beginDiscovery;
    private final Runnable discoveryActionTimeout = this::handleDiscoveryActionTimeout;
    private final Runnable connectionTimeout = this::handleConnectionTimeout;

    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceRequest serviceRequest;
    private boolean receiverRegistered;
    private boolean active;
    private boolean closed;
    private boolean configuringDiscovery;
    private boolean peerDiscoveryRunning;
    private boolean connectionRequested;
    private boolean groupConnected;
    private boolean managedGroup;
    private boolean manualRetryRequired;
    private int generation;
    private String expectedServerId;
    private String expectedServiceName;
    private int matchedPort;
    private State lastState;
    private int lastReason = Integer.MIN_VALUE;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                handleP2pState(intent);
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                handleDiscoveryState(intent);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeerCount();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "P2P connection broadcast; requesting authoritative info");
                requestConnectionInfo();
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "Local P2P device state changed");
            }
        }
    };

    WifiDirectConnectionClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiManager = this.context.getSystemService(WifiManager.class);
        featureSupported = this.context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT
        );
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    void start(String serverId, String serviceName) {
        if (closed || active) return;
        expectedServerId = serverId;
        expectedServiceName = serviceName;
        lastState = null;
        lastReason = Integer.MIN_VALUE;
        active = true;
        manualRetryRequired = false;
        generation++;
        Log.i(TAG, "Direct fallback started for verified Server identity");
        registerReceiver();
        beginDiscovery();
    }

    void retry() {
        if (!active || closed) return;
        Log.i(TAG, "User requested direct-connect retry");
        generation++;
        manualRetryRequired = false;
        connectionRequested = false;
        groupConnected = false;
        cancelOperationCallbacks();
        cancelPendingConnection();
        if (managedGroup) removeManagedGroup(this::beginDiscovery);
        else beginDiscovery();
    }

    void onPermissionOrSettingsChanged() {
        if (!active || closed || groupConnected) return;
        Log.i(TAG, "Permission/settings changed; rechecking direct-connect prerequisites");
        generation++;
        manualRetryRequired = false;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        cancelOperationCallbacks();
        clearServiceRequest();
        beginDiscovery();
    }

    boolean isActive() {
        return active;
    }

    static String requiredRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    static String[] requiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
    }

    static boolean hasRuntimePermission(Context context) {
        return context.checkSelfPermission(requiredRuntimePermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    void stop() {
        if (!active && !receiverRegistered) return;
        Log.i(TAG, "Direct fallback explicitly stopping");
        active = false;
        generation++;
        manualRetryRequired = false;
        cancelOperationCallbacks();
        if (connectionRequested) cancelPendingConnection();
        clearServiceRequest();
        stopPeerDiscovery();
        if (managedGroup) removeManagedGroup(null);
        connectionRequested = false;
        groupConnected = false;
        managedGroup = false;
        expectedServerId = null;
        expectedServiceName = null;
        unregisterReceiver();
    }

    private void beginDiscovery() {
        mainHandler.removeCallbacks(discoveryRetry);
        if (!active || closed || connectionRequested || groupConnected || manualRetryRequired) return;
        if (!featureSupported || manager == null) {
            notifyState(State.UNSUPPORTED, WifiP2pManager.P2P_UNSUPPORTED);
            return;
        }
        if (!hasRuntimePermission(context)) {
            Log.w(TAG, "Direct discovery waiting for Nearby/location permission");
            notifyState(State.PERMISSION_REQUIRED, 0);
            return;
        }
        if (!isLocationModeEnabled()) {
            Log.w(TAG, "Direct discovery waiting for Android Location Mode");
            notifyState(State.LOCATION_DISABLED, 0);
            return;
        }
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            Log.w(TAG, "Direct discovery waiting for Wi-Fi");
            notifyState(State.WIFI_DISABLED, 0);
            return;
        }
        if (!receiverRegistered) {
            registerReceiver();
            if (!receiverRegistered) {
                scheduleDiscoveryRecovery("receiver unavailable");
                return;
            }
        }
        initializeChannel();
        if (channel == null) {
            scheduleDiscoveryRecovery("channel unavailable");
            return;
        }
        if (configuringDiscovery) return;
        notifyState(State.DISCOVERING, 0);
        prepareDiscovery(++generation);
    }

    private void initializeChannel() {
        if (channel != null || manager == null || closed) return;
        try {
            channel = manager.initialize(context, mainHandler.getLooper(), this::onChannelDisconnected);
            Log.i(TAG, "P2P channel initialized");
        } catch (RuntimeException exception) {
            channel = null;
            Log.w(TAG, "Unable to initialize P2P channel", exception);
        }
    }

    private void onChannelDisconnected() {
        Log.w(TAG, "P2P channel disconnected; rebuilding it through retry policy");
        generation++;
        channel = null;
        serviceRequest = null;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        connectionRequested = false;
        groupConnected = false;
        managedGroup = false;
        cancelOperationCallbacks();
        if (active && !closed && !manualRetryRequired) {
            listener.onDirectDisconnected();
            scheduleDiscoveryRecovery("channel disconnected");
        }
    }

    private void prepareDiscovery(int operationGeneration) {
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null || !isCurrent(operationGeneration)) return;
        configuringDiscovery = true;
        mainHandler.removeCallbacks(discoveryActionTimeout);
        mainHandler.postDelayed(discoveryActionTimeout, DISCOVERY_ACTION_TIMEOUT_MILLISECONDS);
        try {
            manager.setDnsSdResponseListeners(
                    currentChannel,
                    (instanceName, registrationType, device) ->
                            Log.d(TAG, "DNS-SD service response received"),
                    (fullDomain, record, device) -> handleTxtRecord(
                            operationGeneration,
                            fullDomain,
                            record,
                            device
                    )
            );
            manager.clearServiceRequests(
                    currentChannel,
                    action(
                            () -> discoverPeers(operationGeneration),
                            reason -> {
                                Log.w(TAG, "clearServiceRequests failed: " + reasonName(reason));
                                discoverPeers(operationGeneration);
                            }
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            mainHandler.removeCallbacks(discoveryActionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            configuringDiscovery = false;
            Log.w(TAG, "Unable to configure P2P discovery", exception);
            scheduleDiscoveryRecovery("configuration exception");
        }
    }

    private void discoverPeers(int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) {
            configuringDiscovery = false;
            return;
        }
        Log.i(TAG, "Starting peer discovery before DNS-SD service discovery");
        try {
            manager.discoverPeers(
                    channel,
                    action(
                            () -> {
                                if (!isCurrent(operationGeneration)) return;
                                peerDiscoveryRunning = true;
                                Log.i(TAG, "Peer discovery started");
                                addServiceRequest(operationGeneration);
                            },
                            reason -> failDiscovery(operationGeneration, "discoverPeers", reason)
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            mainHandler.removeCallbacks(discoveryActionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            failDiscovery(operationGeneration, "discoverPeers", WifiP2pManager.ERROR);
        }
    }

    private void addServiceRequest(int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) {
            configuringDiscovery = false;
            return;
        }
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        try {
            manager.addServiceRequest(
                    channel,
                    serviceRequest,
                    action(
                            () -> discoverServices(operationGeneration),
                            reason -> failDiscovery(
                                    operationGeneration,
                                    "addServiceRequest",
                                    reason
                            )
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            mainHandler.removeCallbacks(discoveryActionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            failDiscovery(operationGeneration, "addServiceRequest", WifiP2pManager.ERROR);
        }
    }

    private void discoverServices(int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) {
            configuringDiscovery = false;
            return;
        }
        Log.i(TAG, "Starting DNS-SD service discovery");
        try {
            manager.discoverServices(
                    channel,
                    action(
                            () -> {
                                if (!isCurrent(operationGeneration)) return;
                                configuringDiscovery = false;
                                peerDiscoveryRunning = true;
                                mainHandler.removeCallbacks(discoveryActionTimeout);
                                recoveryPolicy.onDiscoveryStarted();
                                Log.i(TAG, "DNS-SD discovery active");
                                scheduleDiscoveryRefresh();
                            },
                            reason -> failDiscovery(operationGeneration, "discoverServices", reason)
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            mainHandler.removeCallbacks(discoveryActionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            failDiscovery(operationGeneration, "discoverServices", WifiP2pManager.ERROR);
        }
    }

    private void failDiscovery(int operationGeneration, String operation, int reason) {
        if (!isCurrent(operationGeneration)) return;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        mainHandler.removeCallbacks(discoveryActionTimeout);
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            Log.i(TAG, operation + " reports P2P unsupported");
            notifyState(State.UNSUPPORTED, reason);
        } else {
            Log.w(TAG, operation + " failed: " + reasonName(reason));
            scheduleDiscoveryRecovery(operation + " failed");
        }
    }

    private void handleTxtRecord(
            int operationGeneration,
            String fullDomain,
            Map<String, String> record,
            WifiP2pDevice device
    ) {
        if (!isCurrent(operationGeneration)
                || connectionRequested
                || groupConnected
                || manualRetryRequired
                || device == null
                || device.deviceAddress == null) {
            return;
        }
        int port = RemoteWifiDirectContract.validatedPort(
                fullDomain,
                record,
                expectedServerId
        );
        if (port < 0) return;
        matchedPort = port;
        Log.i(TAG, "Matched verified Server DNS-SD identity; requesting P2P connection");
        connect(device, operationGeneration);
    }

    private void connect(WifiP2pDevice device, int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) return;
        connectionRequested = true;
        peerDiscoveryRunning = false;
        mainHandler.removeCallbacks(discoveryRefresh);
        mainHandler.removeCallbacks(discoveryRetry);
        mainHandler.removeCallbacks(discoveryActionTimeout);
        clearServiceRequest();
        notifyState(State.CONNECTING, 0);

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WifiP2pConfig.GROUP_OWNER_INTENT_MIN
                : LEGACY_GROUP_OWNER_INTENT_MIN;
        try {
            manager.connect(
                    channel,
                    config,
                    action(
                            () -> {
                                if (!isCurrent(operationGeneration)) return;
                                Log.i(TAG, "P2P connect accepted by framework; awaiting group/approval");
                                notifyState(State.WAITING_FOR_APPROVAL, 0);
                                mainHandler.removeCallbacks(connectionTimeout);
                                mainHandler.postDelayed(
                                        connectionTimeout,
                                        CONNECTION_TIMEOUT_MILLISECONDS
                                );
                                requestConnectionInfo();
                            },
                            reason -> {
                                if (!isCurrent(operationGeneration)) return;
                                handleConnectFailure(reason, "connect rejected");
                            }
                    )
            );
        } catch (SecurityException exception) {
            connectionRequested = false;
            mainHandler.removeCallbacks(connectionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            handleConnectFailure(WifiP2pManager.ERROR, "connect exception");
        }
    }

    private void requestConnectionInfo() {
        if (!active || closed || manager == null || channel == null
                || !hasRuntimePermission(context)) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, this::handleConnectionInfo);
        } catch (SecurityException exception) {
            connectionRequested = false;
            mainHandler.removeCallbacks(connectionTimeout);
            cancelPendingConnection();
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to request P2P connection info", exception);
            if (connectionRequested) handleConnectFailure(WifiP2pManager.ERROR, "info failure");
        }
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (!active || closed) return;
        if (groupConnected && info != null && info.groupFormed) return;
        if (info == null || !info.groupFormed) {
            if (groupConnected) {
                Log.w(TAG, "Established P2P group disconnected; starting automatic recovery");
                groupConnected = false;
                managedGroup = false;
                connectionRequested = false;
                listener.onDirectDisconnected();
                scheduleDiscoveryRecovery("group disconnected");
            }
            return;
        }
        if (!connectionRequested) {
            Log.d(TAG, "Ignoring an untracked pre-existing P2P group");
            return;
        }
        managedGroup = true;
        mainHandler.removeCallbacks(connectionTimeout);
        if (info.isGroupOwner) {
            connectionRequested = false;
            manualRetryRequired = true;
            Log.w(TAG, "Phone became P2P group owner; current Server route is unavailable");
            notifyState(State.PHONE_GROUP_OWNER, 0);
            removeManagedGroup(null);
            return;
        }
        InetAddress address = info.groupOwnerAddress;
        if (address == null || matchedPort < 1) {
            handleConnectFailure(WifiP2pManager.ERROR, "missing group-owner endpoint");
            return;
        }
        groupConnected = true;
        connectionRequested = false;
        manualRetryRequired = false;
        recoveryPolicy.onGroupConnected();
        clearServiceRequest();
        Log.i(TAG, "P2P group connected with Server as group owner");
        listener.onDirectEndpoint(new DiscoveredServer(
                expectedServerId,
                expectedServiceName,
                address,
                matchedPort,
                DiscoveredServer.Transport.WIFI_DIRECT
        ));
    }

    private void handleConnectFailure(int reason, String detail) {
        connectionRequested = false;
        mainHandler.removeCallbacks(connectionTimeout);
        cancelPendingConnection();
        Log.w(TAG, detail + ": " + reasonName(reason));
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            notifyState(State.UNSUPPORTED, reason);
        } else if (recoveryPolicy.shouldAutomaticallyRetryConnection()) {
            scheduleDiscoveryRecovery(detail);
        } else {
            manualRetryRequired = true;
            notifyState(State.FAILED, reason);
        }
    }

    private void handleConnectionTimeout() {
        if (!active || !connectionRequested || groupConnected) return;
        handleConnectFailure(WifiP2pManager.ERROR, "connection timeout");
    }

    private void handleDiscoveryActionTimeout() {
        if (!active || !configuringDiscovery || connectionRequested || groupConnected) return;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        Log.w(TAG, "P2P discovery action timed out; retrying automatically");
        scheduleDiscoveryRecovery("discovery action timeout");
    }

    private void refreshDiscovery() {
        if (!active || connectionRequested || groupConnected || manualRetryRequired) return;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        Log.d(TAG, "Refreshing peer and DNS-SD discovery");
        beginDiscovery();
    }

    private void scheduleDiscoveryRefresh() {
        mainHandler.removeCallbacks(discoveryRefresh);
        if (active && !connectionRequested && !groupConnected && !manualRetryRequired) {
            mainHandler.postDelayed(discoveryRefresh, DISCOVERY_REFRESH_MILLISECONDS);
        }
    }

    private void scheduleDiscoveryRecovery(String detail) {
        if (!active || closed || connectionRequested || groupConnected || manualRetryRequired) return;
        configuringDiscovery = false;
        peerDiscoveryRunning = false;
        mainHandler.removeCallbacks(discoveryActionTimeout);
        mainHandler.removeCallbacks(discoveryRefresh);
        clearServiceRequest();
        long delay = recoveryPolicy.nextDiscoveryRetryDelayMilliseconds();
        Log.i(TAG, "Scheduling P2P discovery recovery in " + delay + " ms: " + detail);
        notifyState(State.DISCOVERING, 0);
        mainHandler.removeCallbacks(discoveryRetry);
        mainHandler.postDelayed(discoveryRetry, delay);
    }

    private void handleP2pState(Intent intent) {
        int state = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED
        );
        boolean enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
        Log.i(TAG, "P2P state=" + (enabled ? "enabled" : "disabled"));
        if (!enabled) {
            boolean wasConnected = groupConnected;
            generation++;
            configuringDiscovery = false;
            peerDiscoveryRunning = false;
            connectionRequested = false;
            groupConnected = false;
            managedGroup = false;
            manualRetryRequired = false;
            cancelOperationCallbacks();
            cancelPendingConnection();
            clearServiceRequest();
            notifyState(State.WIFI_DISABLED, 0);
            if (wasConnected) listener.onDirectDisconnected();
        } else if (active && !connectionRequested && !groupConnected && !manualRetryRequired) {
            beginDiscovery();
        }
    }

    private void handleDiscoveryState(Intent intent) {
        int state = intent.getIntExtra(
                WifiP2pManager.EXTRA_DISCOVERY_STATE,
                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
        );
        peerDiscoveryRunning = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
        Log.i(TAG, "P2P discovery state=" + (peerDiscoveryRunning ? "started" : "stopped"));
        if (!peerDiscoveryRunning && active && !configuringDiscovery
                && !connectionRequested && !groupConnected && !manualRetryRequired) {
            scheduleDiscoveryRecovery("framework stopped discovery");
        }
    }

    private void requestPeerCount() {
        if (!active || manager == null || channel == null || !hasRuntimePermission(context)) return;
        try {
            manager.requestPeers(channel, peers ->
                    Log.d(TAG, "P2P peers visible=" + peers.getDeviceList().size()));
        } catch (SecurityException exception) {
            Log.w(TAG, "P2P peer-list permission unavailable", exception);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to request P2P peers", exception);
        }
    }

    private void cancelOperationCallbacks() {
        mainHandler.removeCallbacks(discoveryRefresh);
        mainHandler.removeCallbacks(discoveryRetry);
        mainHandler.removeCallbacks(discoveryActionTimeout);
        mainHandler.removeCallbacks(connectionTimeout);
    }

    private void cancelPendingConnection() {
        if (manager == null || channel == null) return;
        try {
            manager.cancelConnect(channel, null);
        } catch (RuntimeException ignored) {
            // The framework may have no outstanding negotiation.
        }
    }

    private void stopPeerDiscovery() {
        peerDiscoveryRunning = false;
        if (manager == null || channel == null) return;
        try {
            manager.stopPeerDiscovery(channel, null);
        } catch (RuntimeException ignored) {
            // Final service teardown may race framework channel cleanup.
        }
    }

    private void clearServiceRequest() {
        mainHandler.removeCallbacks(discoveryRefresh);
        WifiP2pDnsSdServiceRequest request = serviceRequest;
        serviceRequest = null;
        if (request == null || manager == null || channel == null) return;
        try {
            manager.removeServiceRequest(channel, request, null);
        } catch (RuntimeException ignored) {
            // A channel rebuild also releases its service request.
        }
    }

    private void removeManagedGroup(Runnable completion) {
        if (!managedGroup || manager == null || channel == null) {
            managedGroup = false;
            if (completion != null) completion.run();
            return;
        }
        managedGroup = false;
        try {
            manager.removeGroup(
                    channel,
                    action(
                            () -> {
                                Log.i(TAG, "Managed P2P group removed");
                                if (completion != null) completion.run();
                            },
                            reason -> {
                                Log.w(TAG, "removeGroup failed: " + reasonName(reason));
                                if (completion != null) completion.run();
                            }
                    )
            );
        } catch (RuntimeException exception) {
            if (completion != null) completion.run();
        }
    }

    private boolean isLocationModeEnabled() {
        try {
            return Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Wi-Fi framework broadcasts can originate from a privileged module UID.
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, intentFilter);
            }
            receiverRegistered = true;
            Log.d(TAG, "P2P receiver registered for service lifetime");
        } catch (RuntimeException exception) {
            receiverRegistered = false;
            Log.w(TAG, "Unable to register P2P receiver", exception);
        }
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // The process may already have discarded the registration.
        }
        Log.d(TAG, "P2P receiver unregistered");
    }

    private boolean isCurrent(int operationGeneration) {
        return active && !closed && operationGeneration == generation;
    }

    private void notifyState(State state, int reason) {
        if (!active || closed || (state == lastState && reason == lastReason)) return;
        lastState = state;
        lastReason = reason;
        Log.i(TAG, "Direct state=" + state + ", reason="
                + (reason == 0 && state != State.FAILED ? "none" : reasonName(reason)));
        listener.onDirectStatus(state, reason);
    }

    private static WifiP2pManager.ActionListener action(
            Runnable success,
            ReasonConsumer failure
    ) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                success.run();
            }

            @Override
            public void onFailure(int reason) {
                failure.accept(reason);
            }
        };
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "BUSY";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P_UNSUPPORTED";
            case WifiP2pManager.ERROR:
            default:
                return "ERROR(" + reason + ')';
        }
    }

    private interface ReasonConsumer {
        void accept(int reason);
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "P2P client closed");
    }
}
