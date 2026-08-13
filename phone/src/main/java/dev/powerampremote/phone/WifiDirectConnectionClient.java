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

import java.net.InetAddress;
import java.util.Map;

/** Discovers and joins one previously paired server through Wi-Fi Direct DNS-SD. */
@SuppressWarnings("deprecation")
final class WifiDirectConnectionClient implements AutoCloseable {
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
    private final Runnable discoveryRefresh = this::refreshDiscovery;
    private final Runnable discoveryTimeout = this::handleDiscoveryTimeout;
    private final Runnable connectionTimeout = this::handleConnectionTimeout;

    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceRequest serviceRequest;
    private boolean receiverRegistered;
    private boolean active;
    private boolean closed;
    private boolean configuringDiscovery;
    private boolean connectionRequested;
    private boolean groupConnected;
    private boolean ownsGroup;
    private boolean haltedAfterFailure;
    private boolean discoveryTimeoutScheduled;
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
                int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                );
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    boolean wasConnected = groupConnected;
                    configuringDiscovery = false;
                    connectionRequested = false;
                    groupConnected = false;
                    ownsGroup = false;
                    haltedAfterFailure = false;
                    cancelDiscoveryTimeout();
                    mainHandler.removeCallbacks(connectionTimeout);
                    cancelPendingConnection();
                    clearServiceRequest();
                    notifyState(State.WIFI_DISABLED, 0);
                    if (wasConnected) listener.onDirectDisconnected();
                } else if (active && !connectionRequested && !groupConnected
                        && !haltedAfterFailure) {
                    beginDiscovery();
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                requestConnectionInfo();
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
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
    }

    void start(String serverId, String serviceName) {
        if (closed || active) return;
        expectedServerId = serverId;
        expectedServiceName = serviceName;
        lastState = null;
        lastReason = Integer.MIN_VALUE;
        active = true;
        haltedAfterFailure = false;
        generation++;
        registerReceiver();
        beginDiscovery();
    }

    void retry() {
        if (!active || closed) return;
        haltedAfterFailure = false;
        connectionRequested = false;
        groupConnected = false;
        cancelDiscoveryTimeout();
        mainHandler.removeCallbacks(connectionTimeout);
        cancelPendingConnection();
        if (ownsGroup) {
            removeOwnedGroup(this::beginDiscovery);
        } else {
            beginDiscovery();
        }
    }

    void onPermissionOrSettingsChanged() {
        if (!active || closed || groupConnected) return;
        haltedAfterFailure = false;
        cancelDiscoveryTimeout();
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
        active = false;
        generation++;
        haltedAfterFailure = false;
        mainHandler.removeCallbacks(discoveryRefresh);
        cancelDiscoveryTimeout();
        mainHandler.removeCallbacks(connectionTimeout);
        cancelPendingConnection();
        clearServiceRequest();
        if (ownsGroup) removeOwnedGroup(null);
        connectionRequested = false;
        groupConnected = false;
        expectedServerId = null;
        expectedServiceName = null;
        unregisterReceiver();
    }

    private void beginDiscovery() {
        if (!active || closed || connectionRequested || groupConnected || haltedAfterFailure) return;
        if (!featureSupported || manager == null) {
            cancelDiscoveryTimeout();
            notifyState(State.UNSUPPORTED, WifiP2pManager.P2P_UNSUPPORTED);
            return;
        }
        if (!hasRuntimePermission(context)) {
            cancelDiscoveryTimeout();
            notifyState(State.PERMISSION_REQUIRED, 0);
            return;
        }
        if (!isLocationModeEnabled()) {
            cancelDiscoveryTimeout();
            notifyState(State.LOCATION_DISABLED, 0);
            return;
        }
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            cancelDiscoveryTimeout();
            notifyState(State.WIFI_DISABLED, 0);
            return;
        }
        initializeChannel();
        if (channel == null || configuringDiscovery) {
            if (channel == null) notifyState(State.FAILED, WifiP2pManager.ERROR);
            return;
        }
        notifyState(State.DISCOVERING, 0);
        if (!discoveryTimeoutScheduled) {
            discoveryTimeoutScheduled = true;
            mainHandler.postDelayed(
                    discoveryTimeout,
                    DISCOVERY_ACTION_TIMEOUT_MILLISECONDS
            );
        }
        configureDiscovery(generation);
    }

    private void initializeChannel() {
        if (channel != null || manager == null || closed) return;
        try {
            channel = manager.initialize(
                    context,
                    mainHandler.getLooper(),
                    this::onChannelDisconnected
            );
        } catch (RuntimeException exception) {
            channel = null;
        }
    }

    private void onChannelDisconnected() {
        channel = null;
        serviceRequest = null;
        configuringDiscovery = false;
        haltedAfterFailure = true;
        cancelDiscoveryTimeout();
        if (active && !closed) notifyState(State.FAILED, WifiP2pManager.ERROR);
    }

    private void configureDiscovery(int operationGeneration) {
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null || !isCurrent(operationGeneration)) return;
        configuringDiscovery = true;
        try {
            manager.setDnsSdResponseListeners(
                    currentChannel,
                    (instanceName, registrationType, device) -> {
                        // The TXT record carries the stable identity and listener port.
                    },
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
                            () -> addServiceRequest(operationGeneration),
                            reason -> addServiceRequest(operationGeneration)
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            cancelDiscoveryTimeout();
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            configuringDiscovery = false;
            haltedAfterFailure = true;
            cancelDiscoveryTimeout();
            notifyState(State.FAILED, WifiP2pManager.ERROR);
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
                            reason -> failDiscovery(operationGeneration, reason)
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            cancelDiscoveryTimeout();
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            failDiscovery(operationGeneration, WifiP2pManager.ERROR);
        }
    }

    private void discoverServices(int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) {
            configuringDiscovery = false;
            return;
        }
        try {
            manager.discoverServices(
                    channel,
                    action(
                            () -> {
                                configuringDiscovery = false;
                                scheduleDiscoveryRefresh();
                            },
                            reason -> failDiscovery(operationGeneration, reason)
                    )
            );
        } catch (SecurityException exception) {
            configuringDiscovery = false;
            cancelDiscoveryTimeout();
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            failDiscovery(operationGeneration, WifiP2pManager.ERROR);
        }
    }

    private void failDiscovery(int operationGeneration, int reason) {
        if (!isCurrent(operationGeneration)) return;
        configuringDiscovery = false;
        haltedAfterFailure = true;
        cancelDiscoveryTimeout();
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            notifyState(State.UNSUPPORTED, reason);
        } else {
            notifyState(State.FAILED, reason);
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
                || haltedAfterFailure
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
        cancelDiscoveryTimeout();
        matchedPort = port;
        connect(device, operationGeneration);
    }

    private void connect(WifiP2pDevice device, int operationGeneration) {
        if (!isCurrent(operationGeneration) || manager == null || channel == null) return;
        connectionRequested = true;
        mainHandler.removeCallbacks(discoveryRefresh);
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
                                connectionRequested = false;
                                haltedAfterFailure = true;
                                notifyState(
                                        reason == WifiP2pManager.P2P_UNSUPPORTED
                                                ? State.UNSUPPORTED : State.FAILED,
                                        reason
                                );
                            }
                    )
            );
        } catch (SecurityException exception) {
            connectionRequested = false;
            mainHandler.removeCallbacks(connectionTimeout);
            notifyState(State.PERMISSION_REQUIRED, 0);
        } catch (RuntimeException exception) {
            connectionRequested = false;
            haltedAfterFailure = true;
            notifyState(State.FAILED, WifiP2pManager.ERROR);
        }
    }

    private void requestConnectionInfo() {
        if (!active || closed || manager == null || channel == null || !hasRuntimePermission(context)) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, this::handleConnectionInfo);
        } catch (RuntimeException exception) {
            if (connectionRequested) {
                connectionRequested = false;
                haltedAfterFailure = true;
                mainHandler.removeCallbacks(connectionTimeout);
                notifyState(State.FAILED, WifiP2pManager.ERROR);
            }
        }
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (!active || closed) return;
        if (groupConnected && info != null && info.groupFormed) return;
        if (info == null || !info.groupFormed) {
            if (groupConnected) {
                groupConnected = false;
                ownsGroup = false;
                connectionRequested = false;
                listener.onDirectDisconnected();
                beginDiscovery();
            }
            return;
        }
        if (!connectionRequested) return;
        ownsGroup = true;
        mainHandler.removeCallbacks(connectionTimeout);
        if (info.isGroupOwner) {
            connectionRequested = false;
            haltedAfterFailure = true;
            notifyState(State.PHONE_GROUP_OWNER, 0);
            removeOwnedGroup(null);
            return;
        }
        InetAddress address = info.groupOwnerAddress;
        if (address == null || matchedPort < 1) {
            connectionRequested = false;
            haltedAfterFailure = true;
            notifyState(State.FAILED, WifiP2pManager.ERROR);
            return;
        }
        groupConnected = true;
        clearServiceRequest();
        listener.onDirectEndpoint(new DiscoveredServer(
                expectedServerId,
                expectedServiceName,
                address,
                matchedPort,
                DiscoveredServer.Transport.WIFI_DIRECT
        ));
    }

    private void handleConnectionTimeout() {
        if (!active || !connectionRequested || groupConnected) return;
        connectionRequested = false;
        haltedAfterFailure = true;
        cancelPendingConnection();
        notifyState(State.FAILED, WifiP2pManager.ERROR);
    }

    private void handleDiscoveryTimeout() {
        discoveryTimeoutScheduled = false;
        if (!active || connectionRequested || groupConnected) return;
        haltedAfterFailure = true;
        configuringDiscovery = false;
        clearServiceRequest();
        notifyState(State.FAILED, WifiP2pManager.ERROR);
    }

    private void refreshDiscovery() {
        if (!active || connectionRequested || groupConnected || haltedAfterFailure) return;
        configuringDiscovery = false;
        beginDiscovery();
    }

    private void scheduleDiscoveryRefresh() {
        mainHandler.removeCallbacks(discoveryRefresh);
        if (active && !connectionRequested && !groupConnected && !haltedAfterFailure) {
            mainHandler.postDelayed(discoveryRefresh, DISCOVERY_REFRESH_MILLISECONDS);
        }
    }

    private void cancelDiscoveryTimeout() {
        discoveryTimeoutScheduled = false;
        mainHandler.removeCallbacks(discoveryTimeout);
    }

    private void cancelPendingConnection() {
        if (manager == null || channel == null) return;
        try {
            manager.cancelConnect(channel, null);
        } catch (RuntimeException ignored) {
            // The framework may have no outstanding negotiation.
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
            // Channel teardown also releases the request.
        }
    }

    private void removeOwnedGroup(Runnable completion) {
        if (!ownsGroup || manager == null || channel == null) {
            ownsGroup = false;
            if (completion != null) completion.run();
            return;
        }
        ownsGroup = false;
        try {
            manager.removeGroup(
                    channel,
                    action(
                            () -> {
                                if (completion != null) completion.run();
                            },
                            reason -> {
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
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, intentFilter);
            }
            receiverRegistered = true;
        } catch (RuntimeException exception) {
            receiverRegistered = false;
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
    }

    private boolean isCurrent(int operationGeneration) {
        return active && !closed && operationGeneration == generation;
    }

    private void notifyState(State state, int reason) {
        if (!active || closed || (state == lastState && reason == lastReason)) return;
        lastState = state;
        lastReason = reason;
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

    private interface ReasonConsumer {
        void accept(int reason);
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
    }
}
