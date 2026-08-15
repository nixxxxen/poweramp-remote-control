package dev.powerampremote.server;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity implements RemotePlaybackService.Listener {
    private static final int RUNTIME_PERMISSIONS_REQUEST = 600;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService diagnosticsExecutor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "remote-api-diagnostics");
                thread.setDaemon(true);
                return thread;
            }
    );

    private TextView connectionStatus;
    private ImageView albumArt;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView trackAudioInfo;
    private TextView trackSourceInfo;
    private ProgressBar trackProgress;
    private TextView elapsedTime;
    private TextView durationTime;
    private TextView errorMessage;
    private ImageButton previousButton;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton dislikeButton;
    private Button ratingButton;
    private ImageButton likeButton;
    private ImageButton shuffleButton;
    private TextView serverStatus;
    private TextView serverAddress;
    private TextView serverClients;
    private ImageView serverPairingQr;
    private TextView serverPairingStatus;
    private Button refreshPairingQrButton;
    private Button copyWebTokenButton;

    private RemotePlaybackService.LocalBinder remoteService;
    private PairingOffer pairingOffer;
    private String webUiAccessToken;
    private volatile boolean activityStarted;
    private boolean serviceBindingRequested;
    private boolean powerampInstalled;
    private boolean hasTrack;
    private int playbackState = PowerampContract.STATE_UNKNOWN;
    private int durationSeconds;
    private int anchorPositionSeconds;
    private long anchorRealtimeMilliseconds;
    private long currentAlbumArtId;
    private long displayedAlbumArtId;
    private int currentRating = -1;
    private int shuffleMode = -1;
    private long lastStateRevision = -1L;
    private long lastServerStatusSequence = -1L;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof RemotePlaybackService.LocalBinder)) {
                showServiceUnavailable();
                return;
            }
            remoteService = (RemotePlaybackService.LocalBinder) service;
            webUiAccessToken = remoteService.webUiAccessToken();
            copyWebTokenButton.setEnabled(webUiAccessToken != null);
            remoteService.addListener(MainActivity.this);
            refreshPairingQr();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteService = null;
            renderServiceDisconnected();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            remoteService = null;
            showServiceUnavailable();
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            renderProgress();
            if (activityStarted && playbackState == PowerampContract.STATE_PLAYING) {
                uiHandler.postDelayed(this, 1_000L);
            }
        }
    };

    private final Runnable pairingExpiry = () -> {
        if (remoteService == null || pairingOffer == null
                || !remoteService.isPairingOfferActive(pairingOffer)) {
            serverPairingQr.setAlpha(0.35f);
            serverPairingStatus.setText(R.string.server_pairing_expired);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        try {
            RemotePlaybackService.start(this);
        } catch (RuntimeException exception) {
            showServiceUnavailable();
        }
        requestRuntimePermissions();

        Button syncButton = findViewById(R.id.sync_button);
        syncButton.setOnClickListener(view -> {
            hideError();
            if (remoteService == null || !remoteService.refresh()) {
                showServiceUnavailable();
            }
        });
        previousButton.setOnClickListener(view -> {
            hideError();
            if (remoteService == null || !remoteService.previous()) {
                showServiceUnavailable();
            }
        });
        playPauseButton.setOnClickListener(view -> {
            hideError();
            if (remoteService == null || !remoteService.togglePlayPause()) {
                showServiceUnavailable();
            }
        });
        nextButton.setOnClickListener(view -> {
            hideError();
            if (remoteService == null || !remoteService.next()) {
                showServiceUnavailable();
            }
        });
        dislikeButton.setOnClickListener(view -> {
            hideError();
            int requestedRating = currentRating == 1 ? 0 : 1;
            requestRating(requestedRating);
        });
        ratingButton.setOnClickListener(view -> showRatingDialog());
        likeButton.setOnClickListener(view -> {
            hideError();
            int requestedRating = currentRating == 5 ? 0 : 5;
            requestRating(requestedRating);
        });
        shuffleButton.setOnClickListener(view -> {
            hideError();
            boolean enable = shuffleMode <= PowerampContract.ShuffleModes.NONE;
            if (remoteService == null || !remoteService.setShuffleEnabled(enable)) {
                showServiceUnavailable();
            }
        });
        refreshPairingQrButton.setOnClickListener(view -> refreshPairingQr());
        refreshPairingQrButton.setEnabled(false);
        copyWebTokenButton.setOnClickListener(view -> copyWebUiAccessToken());
        copyWebTokenButton.setEnabled(false);
        renderRemoteServerStatus(
                new RemoteApiServer.Status(
                        false,
                        RemoteApiServer.PORT,
                        0,
                        null,
                        0L
                ),
                null
        );
        renderTransportControls();
        renderSecondaryControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        serviceBindingRequested = bindService(
                RemotePlaybackService.bindingIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
        if (!serviceBindingRequested) {
            showServiceUnavailable();
        }
        restartProgressTicker();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        if (remoteService != null) {
            remoteService.removeListener(this);
            remoteService = null;
        }
        if (serviceBindingRequested) {
            unbindService(serviceConnection);
            serviceBindingRequested = false;
        }
        uiHandler.removeCallbacks(progressTicker);
        uiHandler.removeCallbacks(pairingExpiry);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        diagnosticsExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRemoteStateChanged(RemotePlaybackState state) {
        if (state.revision < lastStateRevision) {
            return;
        }
        lastStateRevision = state.revision;
        powerampInstalled = state.powerampAvailable;
        playbackState = state.playbackState;
        shuffleMode = state.shuffleMode;

        TrackInfo track = state.track;
        hasTrack = track != null;
        if (track == null) {
            durationSeconds = 0;
            currentAlbumArtId = 0L;
            displayedAlbumArtId = 0L;
            currentRating = -1;
            setPositionAnchor(0);
            setWaitingMetadata();
            showAlbumPlaceholder();
        } else {
            durationSeconds = Math.max(track.durationSeconds, 0);
            currentAlbumArtId = track.albumArtId();
            currentRating = track.rating;
            setPositionAnchor(state.positionAvailable
                    ? state.positionAt(SystemClock.elapsedRealtime())
                    : 0);
            trackTitle.setText(valueOrFallback(track.title, R.string.unknown_title));
            trackArtist.setText(valueOrFallback(track.artist, R.string.unknown_artist));
            trackAlbum.setText(valueOrFallback(track.album, R.string.unknown_album));
            renderTrackDetails(track);
            if (displayedAlbumArtId != currentAlbumArtId) {
                showAlbumPlaceholder();
            }
        }

        renderConnectionStatus();
        renderProgress();
        renderTransportControls();
        renderSecondaryControls();
        restartProgressTicker();
    }

    @Override
    public void onRemoteArtworkChanged(long albumArtId, Bitmap bitmap) {
        if (!hasTrack || albumArtId != currentAlbumArtId) {
            return;
        }
        if (bitmap == null) {
            displayedAlbumArtId = 0L;
            showAlbumPlaceholder();
            return;
        }
        displayedAlbumArtId = albumArtId;
        albumArt.setPadding(0, 0, 0, 0);
        albumArt.setImageBitmap(bitmap);
    }

    @Override
    public void onRemotePowerampError(String message) {
        errorMessage.setText(message);
        errorMessage.setVisibility(View.VISIBLE);
    }

    private void bindViews() {
        connectionStatus = findViewById(R.id.connection_status);
        findViewById(R.id.album_art_container).setClipToOutline(true);
        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        trackAudioInfo = findViewById(R.id.track_audio_info);
        trackSourceInfo = findViewById(R.id.track_source_info);
        trackProgress = findViewById(R.id.track_progress);
        elapsedTime = findViewById(R.id.elapsed_time);
        durationTime = findViewById(R.id.duration_time);
        errorMessage = findViewById(R.id.error_message);
        previousButton = findViewById(R.id.previous_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        nextButton = findViewById(R.id.next_button);
        dislikeButton = findViewById(R.id.dislike_button);
        ratingButton = findViewById(R.id.rating_button);
        likeButton = findViewById(R.id.like_button);
        shuffleButton = findViewById(R.id.shuffle_button);
        serverStatus = findViewById(R.id.server_status);
        serverAddress = findViewById(R.id.server_address);
        serverClients = findViewById(R.id.server_clients);
        serverPairingQr = findViewById(R.id.server_pairing_qr);
        serverPairingStatus = findViewById(R.id.server_pairing_status);
        refreshPairingQrButton = findViewById(R.id.refresh_pairing_qr_button);
        copyWebTokenButton = findViewById(R.id.copy_web_token_button);
    }

    private void setWaitingMetadata() {
        trackTitle.setText(R.string.waiting_title);
        trackArtist.setText(R.string.waiting_artist);
        trackAlbum.setText(R.string.waiting_album);
        setTrackDetailText(trackAudioInfo, "");
        setTrackDetailText(trackSourceInfo, "");
    }

    private CharSequence valueOrFallback(String value, int fallbackResource) {
        return value == null || value.trim().isEmpty() ? getText(fallbackResource) : value;
    }

    private void setPositionAnchor(int positionSeconds) {
        anchorPositionSeconds = Math.max(positionSeconds, 0);
        anchorRealtimeMilliseconds = SystemClock.elapsedRealtime();
    }

    private int calculatedPositionSeconds() {
        long position = anchorPositionSeconds;
        if (playbackState == PowerampContract.STATE_PLAYING) {
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - anchorRealtimeMilliseconds);
            position += elapsed / 1_000L;
        }
        if (durationSeconds > 0) {
            position = Math.min(position, durationSeconds);
        }
        return position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) position;
    }

    private void renderProgress() {
        int position = calculatedPositionSeconds();
        trackProgress.setMax(Math.max(durationSeconds, 1));
        trackProgress.setProgress(Math.min(position, Math.max(durationSeconds, 1)));
        elapsedTime.setText(TimeFormatter.formatSeconds(position));
        durationTime.setText(TimeFormatter.formatSeconds(durationSeconds));
    }

    private void restartProgressTicker() {
        uiHandler.removeCallbacks(progressTicker);
        if (activityStarted && playbackState == PowerampContract.STATE_PLAYING) {
            uiHandler.postDelayed(progressTicker, 1_000L);
        }
    }

    private void renderConnectionStatus() {
        int textResource;
        int colorResource;
        if (!powerampInstalled) {
            textResource = R.string.status_not_installed;
            colorResource = R.color.error;
        } else if (!hasTrack) {
            textResource = R.string.status_waiting;
            colorResource = R.color.warning;
        } else if (playbackState == PowerampContract.STATE_PLAYING) {
            textResource = R.string.status_playing;
            colorResource = R.color.accent;
        } else if (playbackState == PowerampContract.STATE_PAUSED) {
            textResource = R.string.status_paused;
            colorResource = R.color.warning;
        } else if (playbackState == PowerampContract.STATE_STOPPED) {
            textResource = R.string.status_stopped;
            colorResource = R.color.text_secondary;
        } else {
            textResource = R.string.status_connected;
            colorResource = R.color.accent;
        }
        connectionStatus.setText(textResource);
        connectionStatus.setTextColor(getColor(colorResource));
    }

    private void renderTransportControls() {
        boolean trackControlsEnabled = powerampInstalled && hasTrack;
        previousButton.setEnabled(trackControlsEnabled);
        playPauseButton.setEnabled(powerampInstalled);
        nextButton.setEnabled(trackControlsEnabled);
        previousButton.setAlpha(trackControlsEnabled ? 1f : 0.38f);
        playPauseButton.setAlpha(powerampInstalled ? 1f : 0.38f);
        nextButton.setAlpha(trackControlsEnabled ? 1f : 0.38f);

        if (playbackState == PowerampContract.STATE_PLAYING) {
            playPauseButton.setImageResource(R.drawable.ic_pause);
            playPauseButton.setContentDescription(getText(R.string.pause));
            setPlayPauseStateDescription(R.string.state_playing_description);
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play);
            playPauseButton.setContentDescription(getText(R.string.play));
            if (playbackState == PowerampContract.STATE_PAUSED) {
                setPlayPauseStateDescription(R.string.state_paused_description);
            } else if (playbackState == PowerampContract.STATE_STOPPED) {
                setPlayPauseStateDescription(R.string.state_stopped_description);
            } else {
                setPlayPauseStateDescription(0);
            }
        }
    }

    private void renderTrackDetails(TrackInfo track) {
        setTrackDetailText(trackAudioInfo, TrackMetadataFormatter.formatAudio(track.audio));
        setTrackDetailText(trackSourceInfo, TrackMetadataFormatter.formatSource(track.source));
    }

    private void setTrackDetailText(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setText(null);
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(value);
        view.setVisibility(View.VISIBLE);
    }

    private void renderSecondaryControls() {
        boolean enabled = powerampInstalled && hasTrack;
        setControlEnabled(dislikeButton, enabled);
        setControlEnabled(ratingButton, enabled);
        setControlEnabled(likeButton, enabled);
        setControlEnabled(shuffleButton, enabled);

        boolean disliked = currentRating == 1;
        boolean liked = currentRating == 5;
        boolean starRated = currentRating >= 2 && currentRating <= 4;
        boolean shuffleEnabled = shuffleMode > PowerampContract.ShuffleModes.NONE;
        dislikeButton.setSelected(disliked);
        ratingButton.setSelected(starRated);
        likeButton.setSelected(liked);
        shuffleButton.setSelected(shuffleEnabled);

        ratingButton.setText(currentRating >= 0
                ? getString(R.string.rating_value, currentRating)
                : getText(R.string.rating_unknown));
        ratingButton.setContentDescription(getText(R.string.rating_change));
        dislikeButton.setContentDescription(getText(disliked
                ? R.string.remove_dislike
                : R.string.dislike));
        likeButton.setContentDescription(getText(liked
                ? R.string.remove_like
                : R.string.like));
        shuffleButton.setContentDescription(getText(shuffleEnabled
                ? R.string.shuffle_disable
                : R.string.shuffle_enable));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ratingButton.setStateDescription(currentRating >= 0
                    ? getString(R.string.rating_current_description, currentRating)
                    : null);
            shuffleButton.setStateDescription(shuffleMode < 0
                    ? null
                    : getText(shuffleEnabled
                            ? R.string.shuffle_on
                            : R.string.shuffle_off));
        }
    }

    private static void setControlEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
    }

    private void showRatingDialog() {
        if (!powerampInstalled || !hasTrack) {
            return;
        }
        hideError();
        new AlertDialog.Builder(this)
                .setTitle(R.string.rating_dialog_title)
                .setSingleChoiceItems(
                        R.array.rating_options,
                        currentRating >= 0 ? currentRating : -1,
                        (dialog, rating) -> {
                            requestRating(rating);
                            dialog.dismiss();
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean requestRating(int rating) {
        if (remoteService == null) {
            showServiceUnavailable();
            return false;
        }
        return remoteService.setRating(rating);
    }

    @Override
    public void onRemoteServerStatusChanged(RemoteApiServer.Status status) {
        try {
            diagnosticsExecutor.execute(() -> {
                String address = LocalNetworkAddress.findIpv4Address();
                uiHandler.post(() -> renderRemoteServerStatus(status, address));
            });
        } catch (RejectedExecutionException ignored) {
            // Activity destruction intentionally stops diagnostic updates.
        }
    }

    private void renderServiceDisconnected() {
        lastStateRevision = -1L;
        lastServerStatusSequence = -1L;
        powerampInstalled = false;
        hasTrack = false;
        playbackState = PowerampContract.STATE_UNKNOWN;
        durationSeconds = 0;
        currentAlbumArtId = 0L;
        displayedAlbumArtId = 0L;
        currentRating = -1;
        shuffleMode = -1;
        setPositionAnchor(0);
        setWaitingMetadata();
        showAlbumPlaceholder();
        renderConnectionStatus();
        renderProgress();
        renderTransportControls();
        renderSecondaryControls();
        renderRemoteServerStatus(
                new RemoteApiServer.Status(
                        false,
                        RemoteApiServer.PORT,
                        0,
                        null,
                        0L
                ),
                null
        );
        pairingOffer = null;
        webUiAccessToken = null;
        serverPairingQr.setImageDrawable(null);
        serverPairingQr.setAlpha(0.35f);
        serverPairingStatus.setText(R.string.server_pairing_unavailable);
        refreshPairingQrButton.setEnabled(false);
        copyWebTokenButton.setEnabled(false);
    }

    private void showServiceUnavailable() {
        errorMessage.setText(R.string.error_service);
        errorMessage.setVisibility(View.VISIBLE);
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!permissions.isEmpty()) {
            requestPermissions(
                    permissions.toArray(new String[0]),
                    RUNTIME_PERMISSIONS_REQUEST
            );
        }
    }

    private void renderRemoteServerStatus(RemoteApiServer.Status status, String address) {
        if (status.sequence < lastServerStatusSequence) {
            return;
        }
        lastServerStatusSequence = status.sequence;
        int statusText;
        int statusColor;
        if (status.running) {
            statusText = R.string.server_status_running;
            statusColor = R.color.accent;
        } else if ("token_unavailable".equals(status.error)) {
            statusText = R.string.server_status_token_error;
            statusColor = R.color.error;
        } else if ("port_unavailable".equals(status.error)) {
            statusText = R.string.server_status_error;
            statusColor = R.color.error;
        } else if (status.error != null) {
            statusText = R.string.server_status_generic_error;
            statusColor = R.color.error;
        } else {
            statusText = R.string.server_status_stopped;
            statusColor = R.color.text_secondary;
        }
        serverStatus.setText(statusText);
        serverStatus.setTextColor(getColor(statusColor));
        serverAddress.setText(address == null
                ? getString(R.string.server_address_unavailable, status.port)
                : getString(R.string.server_address_value, address, status.port));
        serverClients.setText(getString(
                R.string.server_clients_value,
                status.webSocketClients
        ));
    }

    private void refreshPairingQr() {
        uiHandler.removeCallbacks(pairingExpiry);
        PairingOffer offer = remoteService == null ? null : remoteService.issuePairingOffer();
        pairingOffer = offer;
        refreshPairingQrButton.setEnabled(remoteService != null);
        if (offer == null) {
            serverPairingQr.setImageDrawable(null);
            serverPairingQr.setAlpha(0.35f);
            serverPairingStatus.setText(R.string.server_pairing_unavailable);
            return;
        }
        try {
            serverPairingQr.setImageBitmap(PairingQrCode.render(offer.qrPayload(), 720));
            serverPairingQr.setAlpha(1f);
            serverPairingStatus.setText(R.string.server_pairing_active);
            long delay = Math.max(
                    1L,
                    offer.expiresAtMilliseconds - SystemClock.elapsedRealtime()
            );
            uiHandler.postDelayed(pairingExpiry, delay);
        } catch (RuntimeException exception) {
            pairingOffer = null;
            serverPairingQr.setImageDrawable(null);
            serverPairingQr.setAlpha(0.35f);
            serverPairingStatus.setText(R.string.server_pairing_unavailable);
        }
    }

    private void copyWebUiAccessToken() {
        if (webUiAccessToken == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(
                getString(R.string.server_web_token_label),
                webUiAccessToken
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.server_web_token_copied, Toast.LENGTH_SHORT).show();
    }

    private void setPlayPauseStateDescription(int stringResource) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            playPauseButton.setStateDescription(
                    stringResource == 0 ? null : getText(stringResource)
            );
        }
    }

    private void showAlbumPlaceholder() {
        int padding = Math.round(86f * getResources().getDisplayMetrics().density);
        albumArt.setPadding(padding, padding, padding, padding);
        albumArt.setImageResource(R.drawable.ic_album_placeholder);
    }

    private void hideError() {
        errorMessage.setText(null);
        errorMessage.setVisibility(View.GONE);
    }
}
