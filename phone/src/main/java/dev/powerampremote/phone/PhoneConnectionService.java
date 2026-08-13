package dev.powerampremote.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground owner of LAN discovery, Wi-Fi Direct, API connections, and reconnect state.
 *
 * <p>The activity binds only while visible. Unbinding never stops discovery, a P2P group, or the
 * WebSocket; only the notification Stop action or final service destruction closes the runtime.</p>
 */
public final class PhoneConnectionService extends Service
        implements RemoteClientController.Listener {
    static final String ACTION_START =
            "dev.powerampremote.phone.action.START_CONNECTION_SERVICE";
    static final String ACTION_STOP =
            "dev.powerampremote.phone.action.STOP_CONNECTION_SERVICE";

    private static final String TAG = "PhoneConnectionService";
    private static final String NOTIFICATION_CHANNEL_ID = "phone_connection";
    private static final int NOTIFICATION_ID = 700;
    private static final int OPEN_ACTIVITY_REQUEST_CODE = 701;
    private static final int STOP_SERVICE_REQUEST_CODE = 702;

    interface Listener extends RemoteClientController.Listener {
    }

    final class LocalBinder extends Binder {
        void addListener(Listener listener) {
            PhoneConnectionService.this.addListener(listener);
        }

        void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        boolean hasPairing() {
            return controller != null && controller.hasPairing();
        }

        String pairedServiceName() {
            return controller == null ? null : controller.pairedServiceName();
        }

        boolean isConnected() {
            return controller != null && controller.isConnected();
        }

        void pair(DiscoveredServer server, String token) {
            if (controller != null) controller.pair(server, token);
        }

        boolean forgetPairing() {
            if (controller == null) return false;
            pairingServer = null;
            pairingTokenRejected = false;
            currentState = null;
            currentArtwork = null;
            return controller.forgetPairing();
        }

        void play() {
            if (controller != null) controller.play();
        }

        void pause() {
            if (controller != null) controller.pause();
        }

        void previous() {
            if (controller != null) controller.previous();
        }

        void next() {
            if (controller != null) controller.next();
        }

        void seek(int seconds) {
            if (controller != null) controller.seek(seconds);
        }

        void setRating(int rating) {
            if (controller != null) controller.setRating(rating);
        }

        void setShuffle(boolean enabled) {
            if (controller != null) controller.setShuffle(enabled);
        }

        void retryDirectConnection() {
            if (controller != null) controller.retryDirectConnection();
        }

        void retryLanDiscovery() {
            if (controller != null) controller.retryLanDiscovery();
        }

        void onDirectPermissionOrSettingsChanged() {
            if (controller != null) controller.onDirectPermissionOrSettingsChanged();
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LocalBinder binder = new LocalBinder();

    private RemoteClientController controller;
    private NotificationManager notificationManager;
    private RemoteClientController.Status currentStatus =
            RemoteClientController.Status.SEARCHING;
    private long currentRetryDelayMilliseconds;
    private DiscoveredServer pairingServer;
    private boolean pairingTokenRejected;
    private RemoteState currentState;
    private Bitmap currentArtwork;
    private boolean foreground;
    private boolean destroyed;

    static void start(Context context) {
        Intent intent = new Intent(context, PhoneConnectionService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    static Intent bindingIntent(Context context) {
        return new Intent(context, PhoneConnectionService.class);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        controller = new RemoteClientController(this, this);
        Log.i(TAG, "Connection runtime created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Explicit notification Stop requested");
            controller.stop();
            removeForegroundNotification();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        try {
            promoteToForeground();
            controller.start();
            Log.i(TAG, "Connection runtime started or already active");
            return START_STICKY;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to start foreground connection runtime", exception);
            controller.stop();
            removeForegroundNotification();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listeners.clear();
        if (controller != null) controller.close();
        currentArtwork = null;
        mainHandler.removeCallbacksAndMessages(null);
        removeForegroundNotification();
        Log.i(TAG, "Connection runtime destroyed");
        super.onDestroy();
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status status,
            long retryDelayMilliseconds
    ) {
        currentStatus = status;
        currentRetryDelayMilliseconds = retryDelayMilliseconds;
        for (Listener listener : listeners) {
            listener.onStatusChanged(status, retryDelayMilliseconds);
        }
        updateNotification();
    }

    @Override
    public void onPairingRequired(DiscoveredServer server, boolean tokenRejected) {
        pairingServer = server;
        pairingTokenRejected = tokenRejected;
        for (Listener listener : listeners) {
            listener.onPairingRequired(server, tokenRejected);
        }
    }

    @Override
    public void onPairingFailed(RemoteClientController.PairingError error) {
        for (Listener listener : listeners) listener.onPairingFailed(error);
    }

    @Override
    public void onPairingSucceeded(String serviceName) {
        pairingServer = null;
        pairingTokenRejected = false;
        for (Listener listener : listeners) listener.onPairingSucceeded(serviceName);
        updateNotification();
    }

    @Override
    public void onStateChanged(RemoteState state) {
        currentState = state;
        for (Listener listener : listeners) listener.onStateChanged(state);
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        currentArtwork = artwork;
        for (Listener listener : listeners) listener.onArtworkChanged(artwork);
    }

    @Override
    public void onCommandError(boolean authenticationError) {
        for (Listener listener : listeners) listener.onCommandError(authenticationError);
    }

    private void addListener(Listener listener) {
        if (listener == null || destroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> addListener(listener));
            return;
        }
        listeners.addIfAbsent(listener);
        listener.onStatusChanged(currentStatus, currentRetryDelayMilliseconds);
        if (pairingServer != null) {
            listener.onPairingRequired(pairingServer, pairingTokenRejected);
        }
        if (currentState != null) listener.onStateChanged(currentState);
        listener.onArtworkChanged(currentArtwork);
    }

    private void createNotificationChannel() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.connection_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.connection_service_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void promoteToForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private void updateNotification() {
        if (!foreground || notificationManager == null || destroyed) return;
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                OPEN_ACTIVITY_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, PhoneConnectionService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                STOP_SERVICE_REQUEST_CODE,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int contentResource;
        switch (currentStatus) {
            case CONNECTED:
                contentResource = R.string.connection_service_notification_lan;
                break;
            case CONNECTED_DIRECT:
                contentResource = R.string.connection_service_notification_direct;
                break;
            case DIRECT_PERMISSION_REQUIRED:
            case DIRECT_LOCATION_REQUIRED:
            case DIRECT_WIFI_REQUIRED:
            case DIRECT_UNSUPPORTED:
            case DIRECT_ACTION_REQUIRED:
            case AUTH_REQUIRED:
            case ERROR:
                contentResource = R.string.connection_service_notification_action;
                break;
            default:
                contentResource = R.string.connection_service_notification_searching;
                break;
        }

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.connection_service_notification_title))
                .setContentText(getString(contentResource))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(getColor(R.color.accent))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        getString(R.string.connection_service_notification_stop),
                        stopPendingIntent
                ).build());
        String serviceName = controller == null ? null : controller.pairedServiceName();
        if (serviceName != null) builder.setSubText(serviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void removeForegroundNotification() {
        if (!foreground) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
