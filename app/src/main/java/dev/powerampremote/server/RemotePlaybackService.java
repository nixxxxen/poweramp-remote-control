package dev.powerampremote.server;

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
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local foreground owner of the Poweramp integration and remote network runtime.
 *
 * <p>The activity binds only while visible. The started service deliberately keeps the Poweramp
 * receivers, REST server, browser sessions, and WebSockets alive when the activity stops.</p>
 */
public final class RemotePlaybackService extends Service implements PowerampClient.Listener {
    static final String ACTION_START =
            "dev.powerampremote.server.action.START_REMOTE_PLAYBACK_SERVICE";
    static final String ACTION_STOP =
            "dev.powerampremote.server.action.STOP_REMOTE_PLAYBACK_SERVICE";

    private static final String TAG = "RemotePlaybackService";
    private static final String NOTIFICATION_CHANNEL_ID = "remote_server";
    private static final int NOTIFICATION_ID = 600;
    private static final int OPEN_ACTIVITY_REQUEST_CODE = 601;
    private static final int STOP_SERVICE_REQUEST_CODE = 602;

    interface Listener {
        void onRemoteStateChanged(RemotePlaybackState state);

        void onRemoteArtworkChanged(long artworkId, Bitmap bitmap);

        void onRemotePowerampError(String message);

        void onRemoteServerStatusChanged(RemoteApiServer.Status status);
    }

    final class LocalBinder extends Binder {
        void addListener(Listener listener) {
            RemotePlaybackService.this.addListener(listener);
        }

        void removeListener(Listener listener) {
            RemotePlaybackService.this.removeListener(listener);
        }

        String apiToken() {
            return apiToken;
        }

        boolean refresh() {
            if (!lifecycle.isRunning()) {
                return false;
            }
            powerampClient.refresh();
            return true;
        }

        boolean previous() {
            if (!lifecycle.isRunning()) {
                return false;
            }
            powerampClient.skipToPrevious();
            return true;
        }

        boolean togglePlayPause() {
            if (!lifecycle.isRunning()) {
                return false;
            }
            powerampClient.togglePlayPause();
            return true;
        }

        boolean next() {
            if (!lifecycle.isRunning()) {
                return false;
            }
            powerampClient.skipToNext();
            return true;
        }

        boolean setRating(int rating) {
            return setRatingInternal(rating);
        }

        boolean setShuffleEnabled(boolean enabled) {
            return lifecycle.isRunning() && powerampClient.setShuffleEnabled(enabled);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ServiceLifecycleState lifecycle = new ServiceLifecycleState();
    private final LocalBinder binder = new LocalBinder();
    private final PlaybackStateStore.Listener stateListener = this::onStoredStateChanged;

    private PlaybackStateStore stateStore;
    private RemoteArtworkCache artworkCache;
    private PowerampClient powerampClient;
    private RemoteApiServer remoteApiServer;
    private RemoteNsdPublisher nsdPublisher;
    private RemoteWifiDirectPublisher wifiDirectPublisher;
    private SystemMediaVolumeController volumeController;
    private NotificationManager notificationManager;
    private String apiToken;
    private RemoteApiServer.Status serverStatus;
    private Bitmap currentArtwork;
    private long currentArtworkId;
    private boolean foreground;
    private volatile boolean destroyed;

    private final RemoteCommandDispatcher.Target remoteCommandTarget =
            new RemoteCommandDispatcher.Target() {
                @Override
                public void play() {
                    powerampClient.play();
                }

                @Override
                public void pause() {
                    powerampClient.pause();
                }

                @Override
                public void previous() {
                    powerampClient.skipToPrevious();
                }

                @Override
                public void next() {
                    powerampClient.skipToNext();
                }

                @Override
                public void seekTo(int positionSeconds) {
                    powerampClient.seekTo(positionSeconds);
                }

                @Override
                public void setShuffle(boolean enabled) {
                    powerampClient.setShuffleEnabled(enabled);
                }

                @Override
                public void setRating(int rating) {
                    setRatingInternal(rating);
                }

                @Override
                public void setVolume(int volume) {
                    volumeController.setVolume(volume);
                }
            };

    static void start(Context context) {
        Intent intent = new Intent(context, RemotePlaybackService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    static Intent bindingIntent(Context context) {
        return new Intent(context, RemotePlaybackService.class);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();

        stateStore = new PlaybackStateStore(SystemClock.elapsedRealtime());
        stateStore.addListener(stateListener);
        volumeController = new SystemMediaVolumeController(
                this,
                mainHandler,
                this::onSystemMediaVolumeChanged
        );
        artworkCache = new RemoteArtworkCache(stateStore);
        try {
            apiToken = ApiTokenStore.loadOrCreate(this);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to load the remote API token", exception);
            apiToken = null;
        }
        powerampClient = new PowerampClient(this, this);
        serverStatus = new RemoteApiServer.Status(
                false,
                RemoteApiServer.PORT,
                0,
                apiToken == null ? "token_unavailable" : null,
                0L
        );
        if (apiToken != null) {
            remoteApiServer = new RemoteApiServer(
                    RemoteApiServer.PORT,
                    apiToken,
                    stateStore,
                    artworkCache,
                    this::submitRemoteCommand,
                    this::onServerStatusFromNetworkThread,
                    SystemClock::elapsedRealtime
            );
        }
        try {
            String serverId = ServerIdentityStore.loadOrCreate(this);
            try {
                nsdPublisher = new RemoteNsdPublisher(this, mainHandler, serverId);
            } catch (RuntimeException exception) {
                // Discovery is additive: its failure must not stop the existing API server.
                Log.e(TAG, "Unable to initialize NSD publication", exception);
                nsdPublisher = null;
            }
            try {
                wifiDirectPublisher = new RemoteWifiDirectPublisher(
                        this,
                        mainHandler,
                        serverId
                );
            } catch (RuntimeException exception) {
                Log.e(TAG, "Unable to initialize Wi-Fi Direct publication", exception);
                wifiDirectPublisher = null;
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to load the public server identity", exception);
            nsdPublisher = null;
            wifiDirectPublisher = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRuntime();
            removeForegroundNotification();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        try {
            promoteToForeground();
            startRuntime();
            return START_STICKY;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to start foreground remote service", exception);
            stopRuntime();
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
        lifecycle.close();
        listeners.clear();
        if (stateStore != null) {
            stateStore.removeListener(stateListener);
        }
        if (nsdPublisher != null) {
            nsdPublisher.close();
        }
        if (wifiDirectPublisher != null) {
            wifiDirectPublisher.close();
        }
        if (remoteApiServer != null) {
            remoteApiServer.close();
        }
        if (powerampClient != null) {
            powerampClient.close();
        }
        if (volumeController != null) {
            volumeController.close();
        }
        if (artworkCache != null) {
            artworkCache.close();
        }
        currentArtwork = null;
        mainHandler.removeCallbacksAndMessages(null);
        removeForegroundNotification();
        super.onDestroy();
    }

    private void startRuntime() {
        if (lifecycle.start() < 0) {
            return;
        }
        // Both starts are idempotent. Calling them again also retries a previous bind/install error.
        powerampClient.start();
        volumeController.start();
        if (remoteApiServer != null) {
            remoteApiServer.start();
        }
        updateNotification();
    }

    private void stopRuntime() {
        lifecycle.stop();
        if (nsdPublisher != null) {
            nsdPublisher.stop();
        }
        if (wifiDirectPublisher != null) {
            wifiDirectPublisher.stop();
        }
        if (remoteApiServer != null) {
            remoteApiServer.stop();
        }
        if (powerampClient != null) {
            powerampClient.stop();
        }
        if (volumeController != null) {
            volumeController.stop();
        }
    }

    private void onSystemMediaVolumeChanged(SystemMediaVolumeController.State state) {
        stateStore.setVolume(
                state.volume,
                state.volumeMax,
                state.controlAvailable,
                SystemClock.elapsedRealtime()
        );
    }

    private boolean submitRemoteCommand(RemoteCommand command) {
        int generation = lifecycle.runningGeneration();
        if (generation < 0) {
            return false;
        }
        return mainHandler.post(() -> {
            if (lifecycle.isRunning(generation)) {
                RemoteCommandDispatcher.dispatch(command, remoteCommandTarget);
            }
        });
    }

    private boolean setRatingInternal(int rating) {
        if (!lifecycle.isRunning() || !powerampClient.setRating(rating)) {
            return false;
        }
        stateStore.setRating(rating, SystemClock.elapsedRealtime());
        return true;
    }

    @Override
    public void onAvailabilityChanged(boolean installed) {
        stateStore.setPowerampAvailable(installed, SystemClock.elapsedRealtime());
        if (!installed) {
            currentArtwork = null;
            currentArtworkId = 0L;
            artworkCache.update(0L, null);
            dispatchArtwork(0L, null);
        }
    }

    @Override
    public void onTrackChanged(TrackInfo track) {
        stateStore.setTrack(track, SystemClock.elapsedRealtime());
        long nextArtworkId = track.albumArtId();
        if (nextArtworkId != currentArtworkId) {
            currentArtworkId = nextArtworkId;
            currentArtwork = null;
            dispatchArtwork(nextArtworkId, null);
        }
    }

    @Override
    public void onPlaybackStateChanged(int state, int positionSeconds) {
        stateStore.setPlaybackState(state, positionSeconds, SystemClock.elapsedRealtime());
    }

    @Override
    public void onPositionChanged(int positionSeconds) {
        stateStore.setPosition(positionSeconds, SystemClock.elapsedRealtime());
    }

    @Override
    public void onShuffleModeChanged(int shuffleMode) {
        stateStore.setShuffleMode(shuffleMode, SystemClock.elapsedRealtime());
    }

    @Override
    public void onAlbumArtChanged(long albumArtId, Bitmap bitmap) {
        RemotePlaybackState state = stateStore.snapshot();
        if (!state.hasTrack() || state.artworkId != albumArtId) {
            return;
        }
        currentArtworkId = albumArtId;
        currentArtwork = bitmap;
        artworkCache.update(albumArtId, bitmap);
        dispatchArtwork(albumArtId, bitmap);
    }

    @Override
    public void onPowerampError(String message) {
        for (Listener listener : listeners) {
            listener.onRemotePowerampError(message);
        }
    }

    private void onStoredStateChanged(RemotePlaybackState state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchState(state);
        } else {
            mainHandler.post(() -> dispatchState(state));
        }
    }

    private void dispatchState(RemotePlaybackState state) {
        if (destroyed) {
            return;
        }
        for (Listener listener : listeners) {
            listener.onRemoteStateChanged(state);
        }
        updateNotification();
    }

    private void dispatchArtwork(long artworkId, Bitmap bitmap) {
        if (destroyed) {
            return;
        }
        for (Listener listener : listeners) {
            listener.onRemoteArtworkChanged(artworkId, bitmap);
        }
    }

    private void addListener(Listener listener) {
        if (listener == null || destroyed) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> addListener(listener));
            return;
        }
        listeners.addIfAbsent(listener);
        listener.onRemoteStateChanged(stateStore.snapshot());
        if (currentArtworkId > 0L) {
            listener.onRemoteArtworkChanged(currentArtworkId, currentArtwork);
        }
        listener.onRemoteServerStatusChanged(serverStatus);
    }

    private void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void onServerStatusFromNetworkThread(RemoteApiServer.Status status) {
        mainHandler.post(() -> {
            if (destroyed || status.sequence < serverStatus.sequence) {
                return;
            }
            serverStatus = status;
            if (nsdPublisher != null) {
                if (status.running) {
                    nsdPublisher.start();
                } else {
                    nsdPublisher.stop();
                }
            }
            if (wifiDirectPublisher != null) {
                if (status.running) {
                    wifiDirectPublisher.start();
                } else {
                    wifiDirectPublisher.stop();
                }
            }
            for (Listener listener : listeners) {
                listener.onRemoteServerStatusChanged(status);
            }
            updateNotification();
        });
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.remote_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.remote_service_channel_description));
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
        if (!foreground || notificationManager == null || destroyed) {
            return;
        }
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
        Intent stopIntent = new Intent(this, RemotePlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                STOP_SERVICE_REQUEST_CODE,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentText;
        if (serverStatus.running) {
            contentText = getString(
                    R.string.remote_service_notification_running,
                    serverStatus.port,
                    serverStatus.webSocketClients
            );
        } else if (serverStatus.error != null) {
            contentText = getString(R.string.remote_service_notification_error);
        } else {
            contentText = getString(R.string.remote_service_notification_starting);
        }

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.remote_service_notification_title))
                .setContentText(contentText)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(getColor(R.color.accent))
                .addAction(
                        new Notification.Action.Builder(
                                Icon.createWithResource(this, R.drawable.ic_notification),
                                getString(R.string.remote_service_notification_stop),
                                stopPendingIntent
                        ).build()
                );
        RemotePlaybackState state = stateStore.snapshot();
        if (state.track != null && state.track.title != null) {
            builder.setSubText(state.track.title);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void removeForegroundNotification() {
        if (!foreground) {
            return;
        }
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
