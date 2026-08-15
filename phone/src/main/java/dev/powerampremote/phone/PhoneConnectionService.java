package dev.powerampremote.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;

/**
 * Foreground owner of LAN discovery, Wi-Fi Direct, API connections, and reconnect state.
 *
 * <p>The activity binds only while visible. Unbinding never stops discovery, a P2P group, or the
 * WebSocket; only the notification Stop action or final service destruction closes the runtime.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public final class PhoneConnectionService extends MediaSessionService
        implements RemoteClientController.Listener {
    static final String ACTION_START =
            "dev.powerampremote.phone.action.START_CONNECTION_SERVICE";
    static final String ACTION_STOP =
            "dev.powerampremote.phone.action.STOP_CONNECTION_SERVICE";
    private static final String ACTION_LOCAL_BIND =
            "dev.powerampremote.phone.action.BIND_CONNECTION_SERVICE";
    private static final String ACTION_NOTIFICATION_PREVIOUS =
            "dev.powerampremote.phone.action.NOTIFICATION_PREVIOUS";
    private static final String ACTION_NOTIFICATION_PLAY_PAUSE =
            "dev.powerampremote.phone.action.NOTIFICATION_PLAY_PAUSE";
    private static final String ACTION_NOTIFICATION_NEXT =
            "dev.powerampremote.phone.action.NOTIFICATION_NEXT";
    private static final String EXTRA_INTERNAL_TOKEN =
            "dev.powerampremote.phone.extra.INTERNAL_TOKEN";
    private static final String PROCESS_START_TOKEN = UUID.randomUUID().toString();

    private static final String TAG = "PhoneConnectionService";
    private static final String NOTIFICATION_CHANNEL_ID = "phone_connection";
    private static final int NOTIFICATION_ID = 700;
    private static final int OPEN_ACTIVITY_REQUEST_CODE = 701;
    private static final int STOP_SERVICE_REQUEST_CODE = 702;
    private static final int PREVIOUS_REQUEST_CODE = 703;
    private static final int PLAY_PAUSE_REQUEST_CODE = 704;
    private static final int NEXT_REQUEST_CODE = 705;

    interface Listener extends RemoteClientController.Listener {
        void onPlaybackSnapshot(PlaybackUiSnapshot snapshot);
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

        void pair(PairingQrPayload payload) {
            if (controller != null) controller.pair(payload);
        }

        boolean isPairingInProgress() {
            return controller != null && controller.isPairingInProgress();
        }

        PlayerDeviceSnapshot playerDeviceSnapshot() {
            return PhoneConnectionService.this.playerDeviceSnapshot();
        }

        boolean forgetPairing() {
            if (controller == null) return false;
            currentState = null;
            currentPlaybackSnapshot = null;
            currentArtwork = null;
            currentArtworkData = null;
            if (remoteSessionPlayer != null) {
                remoteSessionPlayer.updateArtwork(null);
                remoteSessionPlayer.updateRemoteState(null);
            }
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

        void setVolume(int volume) {
            if (controller != null) controller.setVolume(volume);
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
    private final String notificationActionToken = UUID.randomUUID().toString();

    private RemoteClientController controller;
    private NotificationManager notificationManager;
    private RemoteClientController.Status currentStatus =
            RemoteClientController.Status.SEARCHING;
    private long currentRetryDelayMilliseconds;
    private RemoteState currentState;
    private PlaybackUiSnapshot currentPlaybackSnapshot;
    private Bitmap currentArtwork;
    private byte[] currentArtworkData;
    private RemoteSessionPlayer remoteSessionPlayer;
    private MediaSession mediaSession;
    private boolean foreground;
    private boolean destroyed;

    static void start(Context context) {
        Intent intent = new Intent(context, PhoneConnectionService.class)
                .setAction(ACTION_START)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_INTERNAL_TOKEN, PROCESS_START_TOKEN);
        context.startForegroundService(intent);
    }

    static Intent bindingIntent(Context context) {
        return new Intent(context, PhoneConnectionService.class)
                .setAction(ACTION_LOCAL_BIND)
                .setPackage(context.getPackageName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        controller = new RemoteClientController(this, this);
        createMediaSessionRuntime();
        Log.i(TAG, "Connection runtime created");
    }

    private void createMediaSessionRuntime() {
        if (remoteSessionPlayer != null || mediaSession != null) return;
        remoteSessionPlayer = new RemoteSessionPlayer(
                Looper.getMainLooper(),
                new RemoteSessionPlayer.CommandSink() {
                    @Override
                    public void play() { controller.play(); }

                    @Override
                    public void pause() { controller.pause(); }

                    @Override
                    public void previous() { controller.previous(); }

                    @Override
                    public void next() { controller.next(); }

                    @Override
                    public void seek(int positionSeconds) {
                        controller.seek(positionSeconds);
                    }

                    @Override
                    public void setVolume(int volume) {
                        controller.setVolume(volume);
                    }
                }
        );
        mediaSession = new MediaSession.Builder(this, remoteSessionPlayer)
                .setId("poweramp-remote-phone")
                .setSessionActivity(activityPendingIntent())
                .build();
        remoteSessionPlayer.updateConnection(isConnectedStatus(currentStatus));
        if (currentState != null) remoteSessionPlayer.updateRemoteState(currentState);
        if (currentArtworkData != null) remoteSessionPlayer.updateArtwork(currentArtworkData);
        Log.i(TAG, "MediaSession proxy runtime created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int inheritedResult = super.onStartCommand(intent, flags, startId);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)
                && !PROCESS_START_TOKEN.equals(intent.getStringExtra(EXTRA_INTERNAL_TOKEN))) {
            Log.w(TAG, "Rejected external connection-service start action");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (isNotificationAction(action) && !isTrustedNotificationAction(intent)) {
            Log.w(TAG, "Rejected untrusted notification action=" + action);
            return inheritedResult;
        }
        if (intent != null && !ACTION_START.equals(action)
                && !isNotificationAction(action)
                && !Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            Log.w(TAG, "Rejected unsupported external service start action=" + action);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Explicit notification Stop requested");
            controller.stop();
            currentStatus = RemoteClientController.Status.SEARCHING;
            currentRetryDelayMilliseconds = 0L;
            releaseMediaSessionRuntime();
            removeForegroundNotification();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        try {
            if (ACTION_START.equals(action)) createMediaSessionRuntime();
            if (mediaSession == null || remoteSessionPlayer == null) {
                Log.w(TAG, "Ignoring media start after explicit Stop");
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            promoteToForeground();
            controller.start();
            dispatchTrustedNotificationAction(action);
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
        IBinder mediaBinder = super.onBind(intent);
        if (mediaBinder != null) return mediaBinder;
        if (intent != null
                && ACTION_LOCAL_BIND.equals(intent.getAction())) {
            // LocalBinder exposes no Binder transaction protocol, so an out-of-process caller
            // cannot invoke these package-private methods even though MediaSessionService itself
            // must be exported for Android media controllers.
            return binder;
        }
        Log.w(TAG, "Rejected non-Media3 service binding");
        return null;
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        if (startInForegroundRequired && !foreground) promoteToForeground();
        else updateNotification();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Phone task removed; keeping connection and MediaSession runtime active");
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listeners.clear();
        if (controller != null) controller.close();
        releaseMediaSessionRuntime();
        currentArtwork = null;
        currentArtworkData = null;
        mainHandler.removeCallbacksAndMessages(null);
        removeForegroundNotification();
        Log.i(TAG, "Connection runtime destroyed");
        super.onDestroy();
    }

    private void releaseMediaSessionRuntime() {
        boolean released = mediaSession != null || remoteSessionPlayer != null;
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (remoteSessionPlayer != null) {
            remoteSessionPlayer.release();
            remoteSessionPlayer = null;
        }
        if (released) Log.i(TAG, "MediaSession proxy runtime released");
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status status,
            long retryDelayMilliseconds
    ) {
        if (!isConnectedStatus(status) && isConnectedStatus(currentStatus)
                && currentPlaybackSnapshot != null) {
            currentPlaybackSnapshot = currentPlaybackSnapshot.frozenAt(
                    SystemClock.elapsedRealtime()
            );
        }
        currentStatus = status;
        currentRetryDelayMilliseconds = retryDelayMilliseconds;
        if (remoteSessionPlayer != null) {
            remoteSessionPlayer.updateConnection(isConnectedStatus(status));
        }
        for (Listener listener : listeners) {
            listener.onStatusChanged(status, retryDelayMilliseconds);
        }
        updateNotification();
    }

    @Override
    public void onPairingFailed(RemoteClientController.PairingError error) {
        for (Listener listener : listeners) listener.onPairingFailed(error);
    }

    @Override
    public void onPairingSucceeded(String serviceName) {
        for (Listener listener : listeners) listener.onPairingSucceeded(serviceName);
        updateNotification();
    }

    @Override
    public void onStateChanged(RemoteState state) {
        currentState = state;
        currentPlaybackSnapshot = PlaybackUiSnapshot.anchor(
                state,
                SystemClock.elapsedRealtime()
        );
        if (remoteSessionPlayer != null) remoteSessionPlayer.updateRemoteState(state);
        for (Listener listener : listeners) {
            listener.onStateChanged(state);
            listener.onPlaybackSnapshot(currentPlaybackSnapshot.capturedAt(
                    SystemClock.elapsedRealtime()
            ));
        }
        updateNotification();
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        currentArtwork = artwork;
        for (Listener listener : listeners) listener.onArtworkChanged(artwork);
        updateNotification();
    }

    @Override
    public void onArtworkBytesChanged(byte[] artwork) {
        currentArtworkData = artwork == null ? null : artwork.clone();
        if (remoteSessionPlayer != null) remoteSessionPlayer.updateArtwork(currentArtworkData);
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
        if (currentState != null) {
            listener.onStateChanged(currentState);
            PlaybackUiSnapshot playbackSnapshot = currentPlaybackSnapshot;
            if (playbackSnapshot != null) {
                listener.onPlaybackSnapshot(playbackSnapshot.capturedAt(
                        SystemClock.elapsedRealtime()
                ));
            }
        }
        listener.onArtworkChanged(currentArtwork);
    }

    private PlayerDeviceSnapshot playerDeviceSnapshot() {
        if (controller == null || !controller.hasPairing()) {
            return new PlayerDeviceSnapshot(
                    false,
                    null,
                    null,
                    null,
                    PlayerDeviceSnapshot.Connection.DISCONNECTED,
                    PlayerDeviceSnapshot.Transport.NONE,
                    null,
                    currentStatus,
                    PairingQrPayload.API_VERSION
            );
        }
        DiscoveredServer endpoint = controller.currentEndpoint();
        boolean connected = isConnectedStatus(currentStatus);
        PlayerDeviceSnapshot.Transport transport = PlayerDeviceSnapshot.Transport.NONE;
        if (connected && endpoint != null) {
            transport = endpoint.transport == DiscoveredServer.Transport.WIFI_DIRECT
                    ? PlayerDeviceSnapshot.Transport.WIFI_DIRECT
                    : PlayerDeviceSnapshot.Transport.LAN;
        }
        return new PlayerDeviceSnapshot(
                true,
                controller.pairedDeviceName(),
                controller.pairedServiceName(),
                controller.pairedServerId(),
                connected
                        ? PlayerDeviceSnapshot.Connection.CONNECTED
                        : PlayerDeviceSnapshot.Connection.DISCONNECTED,
                transport,
                endpoint == null ? null : endpoint.addressLabel(),
                currentStatus,
                PairingQrPayload.API_VERSION
        );
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
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
        PendingIntent openPendingIntent = activityPendingIntent();
        PendingIntent stopPendingIntent = notificationPendingIntent(
                ACTION_STOP,
                STOP_SERVICE_REQUEST_CODE
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

        boolean playing = currentState != null
                && "playing".equals(currentState.playbackState);
        String title = currentState != null && currentState.hasTrack
                && currentState.title != null && !currentState.title.trim().isEmpty()
                ? currentState.title : getString(R.string.connection_service_notification_title);
        String detail = currentState != null && currentState.hasTrack
                && currentState.artist != null && !currentState.artist.trim().isEmpty()
                ? currentState.artist : getString(contentResource);

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setColor(getColor(R.color.accent))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_previous),
                        getString(R.string.previous_track),
                        notificationPendingIntent(
                                ACTION_NOTIFICATION_PREVIOUS,
                                PREVIOUS_REQUEST_CODE
                        )
                ).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(
                                this,
                                playing ? R.drawable.ic_pause : R.drawable.ic_play
                        ),
                        getString(playing ? R.string.pause : R.string.play),
                        notificationPendingIntent(
                                ACTION_NOTIFICATION_PLAY_PAUSE,
                                PLAY_PAUSE_REQUEST_CODE
                        )
                ).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next),
                        getString(R.string.next_track),
                        notificationPendingIntent(
                                ACTION_NOTIFICATION_NEXT,
                                NEXT_REQUEST_CODE
                        )
                ).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        getString(R.string.connection_service_notification_stop),
                        stopPendingIntent
                ).build());
        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getPlatformToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        if (currentArtwork != null) builder.setLargeIcon(currentArtwork);
        String serviceName = controller == null ? null : controller.pairedServiceName();
        if (serviceName != null) builder.setSubText(serviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent notificationPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PhoneConnectionService.class)
                .setAction(action)
                .setPackage(getPackageName())
                .putExtra(EXTRA_INTERNAL_TOKEN, notificationActionToken);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent activityPendingIntent() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this,
                OPEN_ACTIVITY_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private boolean isTrustedNotificationAction(Intent intent) {
        return intent != null && notificationActionToken.equals(
                intent.getStringExtra(EXTRA_INTERNAL_TOKEN)
        );
    }

    private static boolean isNotificationAction(String action) {
        return ACTION_STOP.equals(action)
                || ACTION_NOTIFICATION_PREVIOUS.equals(action)
                || ACTION_NOTIFICATION_PLAY_PAUSE.equals(action)
                || ACTION_NOTIFICATION_NEXT.equals(action);
    }

    private void dispatchTrustedNotificationAction(String action) {
        if (remoteSessionPlayer == null) return;
        if (ACTION_NOTIFICATION_PREVIOUS.equals(action)) {
            remoteSessionPlayer.seekToPreviousMediaItem();
        } else if (ACTION_NOTIFICATION_NEXT.equals(action)) {
            remoteSessionPlayer.seekToNextMediaItem();
        } else if (ACTION_NOTIFICATION_PLAY_PAUSE.equals(action)) {
            if (remoteSessionPlayer.getPlayWhenReady()) remoteSessionPlayer.pause();
            else remoteSessionPlayer.play();
        }
    }

    private static boolean isConnectedStatus(RemoteClientController.Status status) {
        return status == RemoteClientController.Status.CONNECTED
                || status == RemoteClientController.Status.CONNECTED_DIRECT;
    }

    private void removeForegroundNotification() {
        if (!foreground) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
