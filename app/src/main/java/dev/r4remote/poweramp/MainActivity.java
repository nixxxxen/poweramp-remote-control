package dev.r4remote.poweramp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity implements PowerampClient.Listener {
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
    private TextView serverToken;
    private TextView serverClients;

    private PowerampClient powerampClient;
    private PlaybackStateStore stateStore;
    private RemoteArtworkCache remoteArtworkCache;
    private RemoteApiServer remoteApiServer;
    private String apiToken;
    private volatile boolean activityStarted;
    private volatile int lifecycleGeneration;
    private boolean powerampInstalled;
    private boolean hasTrack;
    private int playbackState = PowerampContract.STATE_UNKNOWN;
    private int durationSeconds;
    private int anchorPositionSeconds;
    private long anchorRealtimeMilliseconds;
    private long currentTrackId;
    private long currentRealId;
    private long currentAlbumArtId;
    private int currentRating = -1;
    private int shuffleMode = -1;
    private long lastServerStatusSequence = -1L;

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
                    requestRating(rating);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        stateStore = new PlaybackStateStore(SystemClock.elapsedRealtime());
        remoteArtworkCache = new RemoteArtworkCache(stateStore);
        try {
            apiToken = ApiTokenStore.loadOrCreate(this);
        } catch (RuntimeException ignored) {
            apiToken = null;
        }
        powerampClient = new PowerampClient(this, this);
        if (apiToken != null) {
            remoteApiServer = new RemoteApiServer(
                    RemoteApiServer.PORT,
                    apiToken,
                    stateStore,
                    remoteArtworkCache,
                    this::submitRemoteCommand,
                    this::onRemoteServerStatusChanged,
                    SystemClock::elapsedRealtime
            );
        }

        Button syncButton = findViewById(R.id.sync_button);
        syncButton.setOnClickListener(view -> {
            hideError();
            powerampClient.refresh();
        });
        previousButton.setOnClickListener(view -> {
            hideError();
            powerampClient.skipToPrevious();
        });
        playPauseButton.setOnClickListener(view -> {
            hideError();
            powerampClient.togglePlayPause();
        });
        nextButton.setOnClickListener(view -> {
            hideError();
            powerampClient.skipToNext();
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
            powerampClient.setShuffleEnabled(enable);
        });
        Button copyTokenButton = findViewById(R.id.copy_token_button);
        copyTokenButton.setOnClickListener(view -> copyApiToken());
        copyTokenButton.setEnabled(apiToken != null);
        serverToken.setText(apiToken != null ? apiToken : getText(R.string.server_token_unavailable));
        onRemoteServerStatusChanged(
                new RemoteApiServer.Status(
                        false,
                        RemoteApiServer.PORT,
                        0,
                        apiToken == null ? "token_unavailable" : null,
                        0L
                )
        );
        renderTransportControls();
        renderSecondaryControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        lifecycleGeneration++;
        powerampClient.start();
        if (remoteApiServer != null) {
            remoteApiServer.start();
        }
        restartProgressTicker();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        lifecycleGeneration++;
        if (remoteApiServer != null) {
            remoteApiServer.stop();
        }
        uiHandler.removeCallbacks(progressTicker);
        powerampClient.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        diagnosticsExecutor.shutdownNow();
        if (remoteApiServer != null) {
            remoteApiServer.close();
        }
        powerampClient.close();
        remoteArtworkCache.close();
        super.onDestroy();
    }

    @Override
    public void onAvailabilityChanged(boolean installed) {
        stateStore.setPowerampAvailable(installed, SystemClock.elapsedRealtime());
        powerampInstalled = installed;
        if (!installed) {
            playbackState = PowerampContract.STATE_UNKNOWN;
            hasTrack = false;
            durationSeconds = 0;
            currentTrackId = 0L;
            currentRealId = 0L;
            currentAlbumArtId = 0L;
            currentRating = -1;
            shuffleMode = -1;
            setPositionAnchor(0);
            uiHandler.removeCallbacks(progressTicker);
            setWaitingMetadata();
            showAlbumPlaceholder();
            remoteArtworkCache.update(0L, null);
            renderProgress();
        }
        renderConnectionStatus();
        renderTransportControls();
        renderSecondaryControls();
    }

    @Override
    public void onTrackChanged(TrackInfo track) {
        stateStore.setTrack(track, SystemClock.elapsedRealtime());
        boolean hadTrack = hasTrack;
        long nextAlbumArtId = track.albumArtId();
        boolean playbackItemChanged = !hadTrack
                || track.id != currentTrackId
                || track.realId != currentRealId;
        boolean artworkChanged = !hadTrack || nextAlbumArtId != currentAlbumArtId;

        hasTrack = true;
        currentTrackId = track.id;
        currentRealId = track.realId;
        currentAlbumArtId = nextAlbumArtId;
        currentRating = track.rating;
        if (playbackItemChanged || track.durationSeconds > 0) {
            durationSeconds = Math.max(track.durationSeconds, 0);
        }
        if (playbackItemChanged) {
            setPositionAnchor(track.positionSeconds);
        }

        trackTitle.setText(valueOrFallback(track.title, R.string.unknown_title));
        trackArtist.setText(valueOrFallback(track.artist, R.string.unknown_artist));
        trackAlbum.setText(valueOrFallback(track.album, R.string.unknown_album));
        renderTrackDetails(track);
        if (artworkChanged) {
            showAlbumPlaceholder();
        }
        renderConnectionStatus();
        renderProgress();
        renderTransportControls();
        renderSecondaryControls();
    }

    @Override
    public void onPlaybackStateChanged(int state, int positionSeconds) {
        stateStore.setPlaybackState(state, positionSeconds, SystemClock.elapsedRealtime());
        int currentPosition = calculatedPositionSeconds();
        playbackState = state;

        if (positionSeconds >= 0) {
            setPositionAnchor(positionSeconds);
        } else if (state == PowerampContract.STATE_STOPPED) {
            setPositionAnchor(0);
        } else {
            setPositionAnchor(currentPosition);
        }

        renderConnectionStatus();
        renderProgress();
        renderTransportControls();
        restartProgressTicker();
    }

    @Override
    public void onPositionChanged(int positionSeconds) {
        stateStore.setPosition(positionSeconds, SystemClock.elapsedRealtime());
        setPositionAnchor(positionSeconds);
        renderProgress();
        restartProgressTicker();
    }

    @Override
    public void onShuffleModeChanged(int mode) {
        stateStore.setShuffleMode(mode, SystemClock.elapsedRealtime());
        shuffleMode = mode;
        renderSecondaryControls();
    }

    @Override
    public void onAlbumArtChanged(long albumArtId, Bitmap bitmap) {
        if (!hasTrack || albumArtId != currentAlbumArtId) {
            return;
        }
        remoteArtworkCache.update(albumArtId, bitmap);
        if (bitmap == null) {
            showAlbumPlaceholder();
            return;
        }
        albumArt.setPadding(0, 0, 0, 0);
        albumArt.setImageBitmap(bitmap);
    }

    @Override
    public void onPowerampError(String message) {
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
        serverToken = findViewById(R.id.server_token);
        serverClients = findViewById(R.id.server_clients);
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
        if (!powerampClient.setRating(rating)) {
            return false;
        }
        currentRating = rating;
        stateStore.setRating(rating, SystemClock.elapsedRealtime());
        renderSecondaryControls();
        return true;
    }

    private boolean submitRemoteCommand(RemoteCommand command) {
        if (!activityStarted) {
            return false;
        }
        int submittedGeneration = lifecycleGeneration;
        return uiHandler.post(() -> {
            if (!activityStarted || submittedGeneration != lifecycleGeneration) {
                return;
            }
            hideError();
            RemoteCommandDispatcher.dispatch(command, remoteCommandTarget);
        });
    }

    private void onRemoteServerStatusChanged(RemoteApiServer.Status status) {
        try {
            diagnosticsExecutor.execute(() -> {
                String address = LocalNetworkAddress.findIpv4Address();
                uiHandler.post(() -> renderRemoteServerStatus(status, address));
            });
        } catch (RejectedExecutionException ignored) {
            // Activity destruction intentionally stops diagnostic updates.
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

    private void copyApiToken() {
        if (apiToken == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.server_token), apiToken);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.server_token_copied, Toast.LENGTH_SHORT).show();
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
