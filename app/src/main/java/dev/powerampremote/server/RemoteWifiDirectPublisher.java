package dev.powerampremote.server;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

/** Publishes the existing API listener for pre-association Wi-Fi Direct discovery. */
final class RemoteWifiDirectPublisher implements AutoCloseable {
    private static final String TAG = "RemoteWifiDirect";
    private static final long RETRY_DELAY_MILLISECONDS = 5_000L;

    private final Context context;
    private final Handler mainHandler;
    private final WifiP2pManager manager;
    private final WifiP2pDnsSdServiceInfo serviceInfo;
    private final Runnable retry = this::retryCurrentState;
    private final IntentFilter stateFilter = new IntentFilter(
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION
    );

    private WifiP2pManager.Channel channel;
    private boolean desired;
    private boolean registered;
    private boolean adding;
    private boolean removing;
    private boolean unsupported;
    private boolean closed;
    private boolean receiverRegistered;
    private boolean wifiWasDisabled;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            int state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED
            );
            if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                wifiWasDisabled = true;
                registered = false;
                adding = false;
                removing = false;
                mainHandler.removeCallbacks(retry);
                return;
            }
            if (wifiWasDisabled) {
                wifiWasDisabled = false;
                registered = false;
                adding = false;
                removing = false;
            }
            if (desired && !closed) registerIfNeeded();
        }
    };

    RemoteWifiDirectPublisher(
            Context context,
            Handler mainHandler,
            String serverId
    ) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                RemoteNsdContract.SERVICE_NAME,
                RemoteNsdContract.WIFI_DIRECT_SERVICE_TYPE,
                RemoteNsdContract.wifiDirectAttributes(serverId, RemoteApiServer.PORT)
        );
        initializeChannel();
    }

    void start() {
        desired = true;
        registerReceiver();
        registerIfNeeded();
    }

    void stop() {
        desired = false;
        mainHandler.removeCallbacks(retry);
        unregisterReceiver();
        unregisterIfNeeded();
    }

    private void initializeChannel() {
        if (closed || unsupported || manager == null || channel != null) return;
        try {
            channel = manager.initialize(
                    context,
                    mainHandler.getLooper(),
                    this::onChannelDisconnected
            );
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to initialize Wi-Fi Direct", exception);
            channel = null;
            scheduleRetry();
        }
    }

    private void onChannelDisconnected() {
        channel = null;
        registered = false;
        adding = false;
        removing = false;
        if (desired && !closed) scheduleRetry();
    }

    private void registerIfNeeded() {
        mainHandler.removeCallbacks(retry);
        if (!desired || closed || unsupported || adding || removing || registered) return;
        if (!hasRuntimePermission()) {
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
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            adding = false;
                            registered = true;
                            Log.i(TAG, "Published Wi-Fi Direct service");
                            if (!desired || closed) unregisterIfNeeded();
                        }

                        @Override
                        public void onFailure(int reason) {
                            adding = false;
                            registered = false;
                            if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                                unsupported = true;
                                Log.i(TAG, "Wi-Fi Direct is not supported");
                            } else {
                                Log.w(TAG, "Wi-Fi Direct publication failed: " + reason);
                                scheduleRetry();
                            }
                        }
                    }
            );
        } catch (SecurityException exception) {
            adding = false;
            Log.w(TAG, "Nearby Wi-Fi permission is not available", exception);
            scheduleRetry();
        } catch (RuntimeException exception) {
            adding = false;
            Log.w(TAG, "Unable to publish Wi-Fi Direct service", exception);
            scheduleRetry();
        }
    }

    private void unregisterIfNeeded() {
        if (!registered || adding || removing || manager == null || channel == null) return;
        removing = true;
        try {
            manager.removeLocalService(
                    channel,
                    serviceInfo,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            removing = false;
                            registered = false;
                            if (desired && !closed) registerIfNeeded();
                        }

                        @Override
                        public void onFailure(int reason) {
                            removing = false;
                            Log.w(TAG, "Wi-Fi Direct unpublication failed: " + reason);
                            scheduleRetry();
                        }
                    }
            );
        } catch (RuntimeException exception) {
            removing = false;
            Log.w(TAG, "Unable to remove Wi-Fi Direct service", exception);
            scheduleRetry();
        }
    }

    private void retryCurrentState() {
        if (desired) registerIfNeeded();
        else unregisterIfNeeded();
    }

    private boolean hasRuntimePermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerReceiver() {
        if (receiverRegistered || closed) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        stateReceiver,
                        stateFilter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                context.registerReceiver(stateReceiver, stateFilter);
            }
            receiverRegistered = true;
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
    }

    private void scheduleRetry() {
        mainHandler.removeCallbacks(retry);
        if (!closed && !unsupported && (desired || registered)) {
            mainHandler.postDelayed(retry, RETRY_DELAY_MILLISECONDS);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
    }
}
