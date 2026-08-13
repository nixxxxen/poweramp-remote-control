package dev.powerampremote.server;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

/** Publishes API v1 through DNS-SD and keeps the Server discoverable without Android Settings. */
final class RemoteWifiDirectPublisher implements AutoCloseable {
    private static final String TAG = "RemoteWifiDirect";
    private static final long RETRY_DELAY_MILLISECONDS = 5_000L;
    private static final long DISCOVERY_REFRESH_MILLISECONDS = 30_000L;

    private final Context context;
    private final Handler mainHandler;
    private final WifiP2pManager manager;
    private final WifiP2pDnsSdServiceInfo serviceInfo;
    private final Runnable retry = this::retryCurrentState;
    private final Runnable discoveryRefresh = this::refreshPeerDiscovery;
    private final IntentFilter stateFilter = new IntentFilter();

    private WifiP2pManager.Channel channel;
    private boolean desired;
    private boolean registered;
    private boolean adding;
    private boolean removing;
    private boolean peerDiscoveryStarting;
    private boolean peerDiscoveryActive;
    private boolean groupConnected;
    private boolean unsupported;
    private boolean closed;
    private boolean receiverRegistered;
    private boolean wifiWasDisabled;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                handleP2pState(intent);
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                handleDiscoveryState(intent);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "P2P connection broadcast; requesting authoritative info");
                requestConnectionInfo();
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeerCount();
            }
        }
    };

    RemoteWifiDirectPublisher(Context context, Handler mainHandler, String serverId) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                RemoteNsdContract.SERVICE_NAME,
                RemoteNsdContract.WIFI_DIRECT_SERVICE_TYPE,
                RemoteNsdContract.wifiDirectAttributes(serverId, RemoteApiServer.PORT)
        );
        stateFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        stateFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        stateFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        stateFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        initializeChannel();
    }

    void start() {
        if (closed) return;
        desired = true;
        Log.i(TAG, "Wi-Fi Direct publication requested");
        registerReceiver();
        requestConnectionInfo();
        registerIfNeeded();
    }

    void stop() {
        desired = false;
        Log.i(TAG, "Wi-Fi Direct publication stopping");
        mainHandler.removeCallbacks(retry);
        mainHandler.removeCallbacks(discoveryRefresh);
        stopPeerDiscovery();
        unregisterIfNeeded();
        unregisterReceiver();
    }

    private void initializeChannel() {
        if (closed || unsupported || manager == null || channel != null) return;
        try {
            channel = manager.initialize(context, mainHandler.getLooper(), this::onChannelDisconnected);
            Log.i(TAG, "P2P channel initialized");
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to initialize Wi-Fi Direct channel", exception);
            channel = null;
            scheduleRetry();
        }
    }

    private void onChannelDisconnected() {
        Log.w(TAG, "P2P channel disconnected; scheduling reinitialization");
        channel = null;
        registered = false;
        adding = false;
        removing = false;
        peerDiscoveryStarting = false;
        peerDiscoveryActive = false;
        groupConnected = false;
        mainHandler.removeCallbacks(discoveryRefresh);
        if (desired && !closed) scheduleRetry();
    }

    private void registerIfNeeded() {
        mainHandler.removeCallbacks(retry);
        if (!desired || closed || unsupported || adding || removing) return;
        if (!receiverRegistered) {
            registerReceiver();
            if (!receiverRegistered) {
                scheduleRetry();
                return;
            }
        }
        if (registered) {
            startPeerDiscoveryIfNeeded();
            return;
        }
        if (!hasRuntimePermission()) {
            Log.w(TAG, "P2P publication waiting for Nearby/location permission");
            scheduleRetry();
            return;
        }
        initializeChannel();
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null) {
            scheduleRetry();
            return;
        }
        adding = true;
        try {
            manager.addLocalService(
                    currentChannel,
                    serviceInfo,
                    action(
                            () -> {
                                adding = false;
                                registered = true;
                                Log.i(TAG, "Wi-Fi Direct DNS-SD service published");
                                if (!desired || closed) unregisterIfNeeded();
                                else startPeerDiscoveryIfNeeded();
                            },
                            reason -> {
                                adding = false;
                                registered = false;
                                handleOperationFailure("addLocalService", reason);
                            }
                    )
            );
        } catch (SecurityException exception) {
            adding = false;
            Log.w(TAG, "P2P publication permission unavailable", exception);
            scheduleRetry();
        } catch (RuntimeException exception) {
            adding = false;
            Log.w(TAG, "Unable to publish Wi-Fi Direct service", exception);
            scheduleRetry();
        }
    }

    private void startPeerDiscoveryIfNeeded() {
        mainHandler.removeCallbacks(discoveryRefresh);
        if (!desired || closed || unsupported || !registered || groupConnected
                || peerDiscoveryStarting || peerDiscoveryActive) {
            return;
        }
        if (!hasRuntimePermission()) {
            Log.w(TAG, "Peer discovery waiting for Nearby/location permission");
            scheduleRetry();
            return;
        }
        if (!isLocationModeEnabled()) {
            Log.w(TAG, "Peer discovery waiting for Android Location Mode");
            scheduleRetry();
            return;
        }
        initializeChannel();
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null) {
            scheduleRetry();
            return;
        }
        peerDiscoveryStarting = true;
        Log.i(TAG, "Starting P2P peer discovery/listen cycle");
        try {
            manager.discoverPeers(
                    currentChannel,
                    action(
                            () -> {
                                peerDiscoveryStarting = false;
                                peerDiscoveryActive = true;
                                Log.i(TAG, "P2P peer discovery started");
                                schedulePeerDiscoveryRefresh(DISCOVERY_REFRESH_MILLISECONDS);
                            },
                            reason -> {
                                peerDiscoveryStarting = false;
                                peerDiscoveryActive = false;
                                handleOperationFailure("discoverPeers", reason);
                            }
                    )
            );
        } catch (SecurityException exception) {
            peerDiscoveryStarting = false;
            Log.w(TAG, "Peer discovery permission unavailable", exception);
            scheduleRetry();
        } catch (RuntimeException exception) {
            peerDiscoveryStarting = false;
            Log.w(TAG, "Unable to start peer discovery", exception);
            scheduleRetry();
        }
    }

    private void refreshPeerDiscovery() {
        if (!desired || closed || groupConnected) return;
        peerDiscoveryActive = false;
        Log.d(TAG, "Refreshing P2P peer discovery");
        startPeerDiscoveryIfNeeded();
    }

    private void stopPeerDiscovery() {
        peerDiscoveryStarting = false;
        peerDiscoveryActive = false;
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null) return;
        try {
            manager.stopPeerDiscovery(currentChannel, null);
        } catch (RuntimeException ignored) {
            // Explicit service shutdown may race framework channel teardown.
        }
    }

    private void unregisterIfNeeded() {
        if (!registered || adding || removing || manager == null || channel == null) return;
        removing = true;
        try {
            manager.removeLocalService(
                    channel,
                    serviceInfo,
                    action(
                            () -> {
                                removing = false;
                                registered = false;
                                Log.i(TAG, "Wi-Fi Direct DNS-SD service removed");
                                if (desired && !closed) registerIfNeeded();
                            },
                            reason -> {
                                removing = false;
                                Log.w(TAG, "removeLocalService failed: " + reasonName(reason));
                                scheduleRetry();
                            }
                    )
            );
        } catch (RuntimeException exception) {
            removing = false;
            Log.w(TAG, "Unable to remove Wi-Fi Direct service", exception);
            scheduleRetry();
        }
    }

    private void handleP2pState(Intent intent) {
        int state = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED
        );
        boolean enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
        Log.i(TAG, "P2P state=" + (enabled ? "enabled" : "disabled"));
        if (!enabled) {
            wifiWasDisabled = true;
            registered = false;
            adding = false;
            removing = false;
            peerDiscoveryStarting = false;
            peerDiscoveryActive = false;
            groupConnected = false;
            mainHandler.removeCallbacks(retry);
            mainHandler.removeCallbacks(discoveryRefresh);
            return;
        }
        if (wifiWasDisabled) {
            wifiWasDisabled = false;
            registered = false;
            adding = false;
            removing = false;
            peerDiscoveryStarting = false;
            peerDiscoveryActive = false;
        }
        if (desired && !closed) registerIfNeeded();
    }

    private void handleDiscoveryState(Intent intent) {
        int state = intent.getIntExtra(
                WifiP2pManager.EXTRA_DISCOVERY_STATE,
                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
        );
        peerDiscoveryActive = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
        Log.i(TAG, "P2P discovery state=" + (peerDiscoveryActive ? "started" : "stopped"));
        if (!peerDiscoveryActive && desired && registered && !groupConnected) {
            schedulePeerDiscoveryRefresh(RETRY_DELAY_MILLISECONDS);
        }
    }

    private void requestConnectionInfo() {
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null || !hasRuntimePermission()) return;
        try {
            manager.requestConnectionInfo(currentChannel, this::handleConnectionInfo);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to query P2P connection info", exception);
        }
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        boolean formed = info != null && info.groupFormed;
        if (formed != groupConnected) {
            Log.i(TAG, formed
                    ? "P2P group formed; serverGroupOwner=" + info.isGroupOwner
                    : "P2P group disconnected");
        }
        groupConnected = formed;
        if (formed) {
            peerDiscoveryStarting = false;
            peerDiscoveryActive = false;
            mainHandler.removeCallbacks(discoveryRefresh);
        } else if (desired && registered) {
            schedulePeerDiscoveryRefresh(0L);
        }
    }

    private void requestPeerCount() {
        WifiP2pManager.Channel currentChannel = channel;
        if (manager == null || currentChannel == null || !hasRuntimePermission()) return;
        try {
            manager.requestPeers(currentChannel, peers ->
                    Log.d(TAG, "P2P peers visible=" + peers.getDeviceList().size()));
        } catch (SecurityException exception) {
            Log.w(TAG, "P2P peer-list permission unavailable", exception);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to query P2P peers", exception);
        }
    }

    private void handleOperationFailure(String operation, int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            unsupported = true;
            Log.i(TAG, operation + " reports P2P unsupported");
        } else {
            Log.w(TAG, operation + " failed: " + reasonName(reason));
            scheduleRetry();
        }
    }

    private void retryCurrentState() {
        if (desired) {
            registerIfNeeded();
            startPeerDiscoveryIfNeeded();
        } else {
            unregisterIfNeeded();
        }
    }

    private boolean hasRuntimePermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean isLocationModeEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                LocationManager locationManager = context.getSystemService(LocationManager.class);
                return locationManager != null && locationManager.isLocationEnabled();
            }
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
        if (receiverRegistered || closed) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Wi-Fi framework broadcasts can originate from a privileged module UID.
                context.registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(stateReceiver, stateFilter);
            }
            receiverRegistered = true;
            Log.d(TAG, "P2P receiver registered");
        } catch (RuntimeException exception) {
            receiverRegistered = false;
            Log.w(TAG, "Unable to observe Wi-Fi Direct state", exception);
        }
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            context.unregisterReceiver(stateReceiver);
        } catch (RuntimeException ignored) {
            // Service teardown may race process-level receiver cleanup.
        }
        Log.d(TAG, "P2P receiver unregistered");
    }

    private void scheduleRetry() {
        mainHandler.removeCallbacks(retry);
        if (!closed && !unsupported && (desired || registered)) {
            mainHandler.postDelayed(retry, RETRY_DELAY_MILLISECONDS);
        }
    }

    private void schedulePeerDiscoveryRefresh(long delayMilliseconds) {
        mainHandler.removeCallbacks(discoveryRefresh);
        if (desired && registered && !closed && !unsupported && !groupConnected) {
            mainHandler.postDelayed(discoveryRefresh, Math.max(0L, delayMilliseconds));
        }
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
    }
}
