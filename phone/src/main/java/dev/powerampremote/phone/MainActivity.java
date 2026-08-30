package dev.powerampremote.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Compact playback-only phone surface backed by the existing foreground connection service. */
public final class MainActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 701;
    private static final String STATE_OPENED_DEVICES = "opened_devices";
    private static final String STATE_THEME_CURRENT = "theme_current";
    private static final String STATE_THEME_TARGET = "theme_target";
    private static final String STATE_THEME_MOTION = "theme_motion";
    private static final long ARTWORK_FALLBACK_DELAY_MILLISECONDS = 1_500L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ArtworkThemeRequestGate artworkThemeRequestGate =
            new ArtworkThemeRequestGate();
    private final ArtworkPaletteRepository artworkPaletteRepository =
            ArtworkPaletteRepository.get();

    private ArtworkThemeBackgroundView artworkThemeBackground;
    private ImageView albumArt;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView codecChip;
    private TextView bitDepthChip;
    private TextView sampleRateChip;
    private TextView bitrateChip;
    private TextView sourceInfo;
    private SeekBar trackSeek;
    private TextView elapsedTime;
    private TextView durationTime;
    private View volumePanel;
    private SeekBar volumeSeek;
    private TextView volumeValue;
    private ImageButton previousButton;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton dislikeButton;
    private Button ratingButton;
    private ImageButton likeButton;
    private ImageButton shuffleButton;
    private TextView errorMessage;
    private RemoteMetadataFormatter metadataFormatter;

    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status status = RemoteClientController.Status.SEARCHING;
    private RemoteState state;
    private boolean activityStarted;
    private boolean bindingRequested;
    private boolean openedDevicesForMissingPairing;
    private boolean draggingSeek;
    private boolean draggingVolume;
    private int durationSeconds;
    private int anchorPositionSeconds;
    private long anchorRealtimeMilliseconds;
    private boolean anchorAdvancing;
    private PlaybackUiSnapshot latestPlaybackSnapshot;
    private String trackIdentity;
    private Integer pendingSeekSeconds;
    private long pendingSeekExpiresRealtimeMilliseconds;
    private Integer pendingVolume;
    private long pendingVolumeExpiresRealtimeMilliseconds;
    private String artworkThemeIdentity;
    private String artworkServerIdentity;
    private boolean artworkServerIdentityInitialized;
    private String artworkPaletteKey;
    private ArtworkThemeRequestGate.Request artworkThemeRequest;
    private Runnable pendingArtworkFallback;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showError(R.string.connection_service_error);
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            updateArtworkServerIdentity(controller.playerDeviceSnapshot().serverId);
            controller.addListener(MainActivity.this);
            if (!controller.hasPairing() && !openedDevicesForMissingPairing) {
                openedDevicesForMissingPairing = true;
                uiHandler.post(MainActivity.this::openPlayerDevices);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            freezeProgress();
            controller = null;
            status = RemoteClientController.Status.ERROR;
            renderControls();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            controller = null;
            showError(R.string.connection_service_error);
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            renderProgress();
            if (shouldAdvanceProgress()) uiHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        openedDevicesForMissingPairing = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_OPENED_DEVICES, false);
        setContentView(R.layout.activity_main);
        SafeDrawingInsets.apply(findViewById(R.id.player_content));
        metadataFormatter = RemoteMetadataFormatter.from(this);
        bindViews();
        restoreArtworkTheme(savedInstanceState);
        configureControls();
        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
        }
        requestNotificationPermission();
        renderPlayer();
        renderProgress();
        renderControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        artworkThemeBackground.onHostStart();
        bindingRequested = bindService(
                PhoneConnectionService.bindingIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
        if (!bindingRequested) showError(R.string.connection_service_error);
        restartProgressTicker();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        uiHandler.removeCallbacks(progressTicker);
        cancelPendingArtworkFallback();
        artworkThemeBackground.onHostStop();
        if (controller != null) {
            controller.removeListener(this);
            controller = null;
        }
        if (bindingRequested) {
            unbindService(serviceConnection);
            bindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        artworkThemeRequestGate.invalidate();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_OPENED_DEVICES, openedDevicesForMissingPairing);
        outState.putIntArray(
                STATE_THEME_CURRENT,
                artworkThemeBackground.currentPaletteSnapshot().toStoredColors()
        );
        outState.putIntArray(
                STATE_THEME_TARGET,
                artworkThemeBackground.targetPaletteSnapshot().toStoredColors()
        );
        outState.putFloat(STATE_THEME_MOTION, artworkThemeBackground.motionPhaseSnapshot());
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        artworkThemeBackground = findViewById(R.id.artwork_theme_background);
        findViewById(R.id.album_art_container).setClipToOutline(true);
        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        codecChip = findViewById(R.id.codec_chip);
        bitDepthChip = findViewById(R.id.bit_depth_chip);
        sampleRateChip = findViewById(R.id.sample_rate_chip);
        bitrateChip = findViewById(R.id.bitrate_chip);
        sourceInfo = findViewById(R.id.source_info);
        trackSeek = findViewById(R.id.track_seek);
        elapsedTime = findViewById(R.id.elapsed_time);
        durationTime = findViewById(R.id.duration_time);
        volumePanel = findViewById(R.id.volume_panel);
        volumeSeek = findViewById(R.id.volume_seek);
        volumeValue = findViewById(R.id.volume_value);
        previousButton = findViewById(R.id.previous_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        nextButton = findViewById(R.id.next_button);
        dislikeButton = findViewById(R.id.dislike_button);
        ratingButton = findViewById(R.id.rating_button);
        likeButton = findViewById(R.id.like_button);
        shuffleButton = findViewById(R.id.shuffle_button);
        errorMessage = findViewById(R.id.error_message);
    }

    private void configureControls() {
        findViewById(R.id.main_menu_button).setOnClickListener(view -> {
            haptic(view);
            showMainMenu(view);
        });
        findViewById(R.id.player_devices_button).setOnClickListener(view -> {
            haptic(view);
            openPlayerDevices();
        });
        previousButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (ensureServiceAvailable()) controller.previous();
        });
        playPauseButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (!ensureServiceAvailable()) return;
            if (state != null && "playing".equals(state.playbackState)) controller.pause();
            else controller.play();
        });
        nextButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (ensureServiceAvailable()) controller.next();
        });
        dislikeButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (ensureServiceAvailable()) {
                controller.setRating(
                        state != null && Integer.valueOf(1).equals(state.rating) ? 0 : 1
                );
            }
        });
        ratingButton.setOnClickListener(view -> {
            haptic(view);
            showRatingDialog();
        });
        likeButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (ensureServiceAvailable()) {
                controller.setRating(
                        state != null && Integer.valueOf(5).equals(state.rating) ? 0 : 5
                );
            }
        });
        shuffleButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (ensureServiceAvailable()) {
                controller.setShuffle(state == null || !Boolean.TRUE.equals(state.shuffle));
            }
        });
        trackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) elapsedTime.setText(TimeFormatter.formatSeconds(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                draggingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int requestedPosition = seekBar.getProgress();
                draggingSeek = false;
                setPositionAnchor(requestedPosition, SystemClock.elapsedRealtime());
                pendingSeekSeconds = requestedPosition;
                pendingSeekExpiresRealtimeMilliseconds = SystemClock.elapsedRealtime() + 2_000L;
                haptic(seekBar);
                hideError();
                if (ensureServiceAvailable()) controller.seek(requestedPosition);
                renderProgress();
            }
        });
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    volumeValue.setText(getString(
                            R.string.volume_value,
                            progress,
                            seekBar.getMax()
                    ));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                draggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int requestedVolume = seekBar.getProgress();
                draggingVolume = false;
                pendingVolume = requestedVolume;
                pendingVolumeExpiresRealtimeMilliseconds = SystemClock.elapsedRealtime() + 2_000L;
                haptic(seekBar);
                hideError();
                if (ensureServiceAvailable()) controller.setVolume(requestedVolume);
                renderVolume();
                uiHandler.postDelayed(() -> {
                    if (activityStarted) renderVolume();
                }, 2_050L);
            }
        });
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status newStatus,
            long retryDelayMilliseconds
    ) {
        if (isConnectedStatus(status) && !isConnectedStatus(newStatus)) freezeProgress();
        status = newStatus;
        renderControls();
        restartProgressTicker();
        if (newStatus == RemoteClientController.Status.AUTH_REQUIRED) {
            showError(R.string.status_auth_required);
        }
    }

    @Override
    public void onPairingFailed(RemoteClientController.PairingError error) {
        // Player devices owns detailed pairing feedback.
    }

    @Override
    public void onPairingSucceeded(String serviceName) {
        hideError();
    }

    @Override
    public void onStateChanged(RemoteState newState, long receivedRealtimeMilliseconds) {
        long now = SystemClock.elapsedRealtime();
        String nextTrackIdentity = newState.trackIdentity();
        boolean trackChanged = trackIdentity != null && !trackIdentity.equals(nextTrackIdentity);
        trackIdentity = nextTrackIdentity;
        state = newState;
        if (isConnectedStatus(status)) updateArtworkThemeRequest(newState);
        durationSeconds = newState.durationSeconds == null ? 0 : newState.durationSeconds;
        if (trackChanged) {
            pendingSeekSeconds = null;
            draggingSeek = false;
        } else if (!newState.hasTrack) {
            pendingSeekSeconds = null;
            setPositionAnchor(0, now);
        }
        renderPlayer();
        renderVolume();
        renderControls();
    }

    @Override
    public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) {
        if (snapshot == null || draggingSeek) return;
        latestPlaybackSnapshot = snapshot;
        anchorAdvancing = snapshot.isAdvancing();
        long now = SystemClock.elapsedRealtime();
        boolean seekConfirmed = pendingSeekSeconds != null
                && Math.abs(snapshot.positionSeconds - pendingSeekSeconds) <= 2;
        if (pendingSeekSeconds == null
                || seekConfirmed
                || now >= pendingSeekExpiresRealtimeMilliseconds) {
            pendingSeekSeconds = null;
            setPositionAnchor(
                    snapshot.positionSeconds,
                    snapshot.capturedRealtimeMilliseconds
            );
        }
        renderProgress();
        restartProgressTicker();
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        if (artwork == null) {
            int padding = getResources().getDimensionPixelSize(R.dimen.album_placeholder_padding);
            albumArt.setPadding(padding, padding, padding, padding);
            albumArt.setImageResource(R.drawable.ic_album_placeholder);
            handleMissingArtworkPalette();
        } else {
            albumArt.setPadding(0, 0, 0, 0);
            albumArt.setImageBitmap(artwork);
            requestArtworkPalette(artwork);
        }
    }

    private void updateArtworkThemeRequest(RemoteState remoteState) {
        String stateArtworkKey = remoteState == null ? null : remoteState.artworkKey();
        String nextPaletteKey = ArtworkPaletteCacheKey.create(
                artworkServerIdentity,
                stateArtworkKey
        );
        String trackKey = remoteState == null || !remoteState.hasTrack
                ? "no-track" : remoteState.trackIdentity();
        String nextIdentity = nextPaletteKey == null
                ? "fallback\u0000" + String.valueOf(artworkServerIdentity) + '\u0000' + trackKey
                : "artwork\u0000" + nextPaletteKey;
        if (Objects.equals(artworkThemeIdentity, nextIdentity)) return;

        cancelPendingArtworkFallback();
        artworkThemeIdentity = nextIdentity;
        artworkPaletteKey = nextPaletteKey;
        artworkThemeRequest = artworkThemeRequestGate.begin(nextIdentity);
        if (nextPaletteKey == null) {
            artworkThemeBackground.setPalette(ArtworkPalette.FALLBACK);
            return;
        }
        ArtworkPalette cached = artworkPaletteRepository.getCached(nextPaletteKey);
        if (cached != null && artworkThemeRequestGate.accepts(artworkThemeRequest)) {
            artworkThemeBackground.setPalette(cached);
        }
        // If this key is not cached, retain the old visual palette until artwork arrives.
    }

    private void updateArtworkServerIdentity(String nextServerIdentity) {
        boolean changed = artworkServerIdentityInitialized
                && !Objects.equals(artworkServerIdentity, nextServerIdentity);
        artworkServerIdentity = nextServerIdentity;
        artworkServerIdentityInitialized = true;
        if (changed) updateArtworkThemeRequest(null);
    }

    private void handleMissingArtworkPalette() {
        cancelPendingArtworkFallback();
        ArtworkThemeRequestGate.Request request = artworkThemeRequest;
        String paletteKey = artworkPaletteKey;
        if (paletteKey == null) {
            if (artworkThemeRequestGate.accepts(request)) {
                artworkThemeBackground.setPalette(ArtworkPalette.FALLBACK);
            }
            return;
        }
        ArtworkPalette cached = artworkPaletteRepository.getCached(paletteKey);
        if (cached != null) {
            if (artworkThemeRequestGate.accepts(request)) {
                artworkThemeBackground.setPalette(cached);
            }
            scheduleArtworkFallback(request, paletteKey);
            return;
        }
        scheduleArtworkFallback(request, paletteKey);
    }

    private void scheduleArtworkFallback(
            ArtworkThemeRequestGate.Request request,
            String paletteKey
    ) {
        cancelPendingArtworkFallback();
        pendingArtworkFallback = () -> {
            pendingArtworkFallback = null;
            if (artworkThemeRequestGate.accepts(request)
                    && Objects.equals(paletteKey, artworkPaletteKey)) {
                artworkThemeBackground.setPalette(ArtworkPalette.FALLBACK);
            }
        };
        uiHandler.postDelayed(
                pendingArtworkFallback,
                ARTWORK_FALLBACK_DELAY_MILLISECONDS
        );
    }

    private void requestArtworkPalette(Bitmap artwork) {
        cancelPendingArtworkFallback();
        String paletteKey = artworkPaletteKey;
        ArtworkThemeRequestGate.Request request = artworkThemeRequest;
        if (paletteKey == null || !artworkThemeRequestGate.accepts(request)) return;

        ArtworkPalette cached = artworkPaletteRepository.getCached(paletteKey);
        if (cached != null) {
            artworkThemeBackground.setPalette(cached);
            return;
        }
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        boolean accepted = artworkPaletteRepository.request(
                paletteKey,
                artwork,
                (completedKey, palette) -> {
                    MainActivity activity = owner.get();
                    if (activity != null) {
                        activity.applyArtworkPalette(request, completedKey, palette);
                    }
                }
        );
        if (!accepted) scheduleArtworkFallback(request, paletteKey);
    }

    private void applyArtworkPalette(
            ArtworkThemeRequestGate.Request request,
            String completedKey,
            ArtworkPalette palette
    ) {
        if (!artworkThemeRequestGate.accepts(request)
                || !Objects.equals(completedKey, artworkPaletteKey)) {
            return;
        }
        cancelPendingArtworkFallback();
        artworkThemeBackground.setPalette(palette);
    }

    private void cancelPendingArtworkFallback() {
        Runnable pending = pendingArtworkFallback;
        pendingArtworkFallback = null;
        if (pending != null) uiHandler.removeCallbacks(pending);
    }

    private void restoreArtworkTheme(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        ArtworkPalette current = ArtworkPalette.fromStoredColors(
                savedInstanceState.getIntArray(STATE_THEME_CURRENT)
        );
        ArtworkPalette target = ArtworkPalette.fromStoredColors(
                savedInstanceState.getIntArray(STATE_THEME_TARGET)
        );
        if (current == null) return;
        artworkThemeBackground.restoreState(
                current,
                target,
                savedInstanceState.getFloat(STATE_THEME_MOTION, 0f)
        );
    }

    @Override
    public void onCommandError(boolean authenticationError) {
        if (pendingSeekSeconds != null) {
            pendingSeekSeconds = null;
            long now = SystemClock.elapsedRealtime();
            PlaybackUiSnapshot confirmed = latestPlaybackSnapshot == null
                    ? null : latestPlaybackSnapshot.capturedAt(now);
            setPositionAnchor(
                    confirmed == null ? 0 : confirmed.positionSeconds,
                    confirmed == null ? now : confirmed.capturedRealtimeMilliseconds
            );
            renderProgress();
        }
        if (pendingVolume != null) {
            pendingVolume = null;
            renderVolume();
        }
        if (!authenticationError) showError(R.string.command_error);
    }

    private void renderPlayer() {
        if (state == null || !state.hasTrack) {
            trackTitle.setText(R.string.waiting_title);
            trackArtist.setText(R.string.waiting_artist);
            trackAlbum.setText(R.string.waiting_album);
            setOptionalText(codecChip, null);
            setOptionalText(bitDepthChip, null);
            setOptionalText(sampleRateChip, null);
            setOptionalText(bitrateChip, null);
            setOptionalText(sourceInfo, null);
            durationSeconds = 0;
            return;
        }
        trackTitle.setText(valueOrFallback(state.title, R.string.unknown_title));
        trackArtist.setText(valueOrFallback(state.artist, R.string.unknown_artist));
        trackAlbum.setText(valueOrFallback(state.album, R.string.unknown_album));
        setOptionalText(codecChip, metadataFormatter.codec(state));
        setOptionalText(bitDepthChip, metadataFormatter.bitDepth(state));
        setOptionalText(sampleRateChip, metadataFormatter.sampleRate(state));
        setOptionalText(bitrateChip, metadataFormatter.bitrate(state));
        setOptionalText(sourceInfo, metadataFormatter.source(state));
    }

    private void renderProgress() {
        if (draggingSeek) return;
        int position = calculatedPositionSeconds();
        trackSeek.setMax(Math.max(durationSeconds, 1));
        trackSeek.setProgress(Math.min(position, Math.max(durationSeconds, 1)));
        elapsedTime.setText(TimeFormatter.formatSeconds(position));
        durationTime.setText(TimeFormatter.formatSeconds(durationSeconds));
    }

    private void renderVolume() {
        if (state == null || state.volume == null || state.volumeMax == null
                || state.volumeMax <= 0) {
            volumePanel.setVisibility(View.VISIBLE);
            volumeSeek.setMax(1);
            if (!draggingVolume) volumeSeek.setProgress(0);
            volumeValue.setText(R.string.volume_value_placeholder);
            return;
        }
        volumePanel.setVisibility(View.VISIBLE);
        volumeSeek.setMax(state.volumeMax);
        long now = SystemClock.elapsedRealtime();
        boolean confirmed = pendingVolume != null && pendingVolume.equals(state.volume);
        if (pendingVolume != null
                && (confirmed || now >= pendingVolumeExpiresRealtimeMilliseconds)) {
            pendingVolume = null;
        }
        if (!draggingVolume && pendingVolume == null) {
            volumeSeek.setProgress(Math.min(state.volume, state.volumeMax));
        }
        volumeValue.setText(getString(
                R.string.volume_value,
                volumeSeek.getProgress(),
                state.volumeMax
        ));
    }

    private void renderControls() {
        boolean connected = isConnectedStatus(status) && state != null;
        boolean apiReady = connected && state.powerampAvailable;
        boolean trackReady = apiReady && state.hasTrack;
        setControlEnabled(previousButton, trackReady);
        setControlEnabled(playPauseButton, apiReady);
        setControlEnabled(nextButton, trackReady);
        setControlEnabled(trackSeek, trackReady && durationSeconds > 0);
        setControlEnabled(dislikeButton, trackReady);
        setControlEnabled(ratingButton, trackReady);
        setControlEnabled(likeButton, trackReady);
        setControlEnabled(shuffleButton, trackReady);
        setControlEnabled(
                volumeSeek,
                connected && Boolean.TRUE.equals(state.volumeControlAvailable)
        );

        boolean playing = state != null && "playing".equals(state.playbackState);
        playPauseButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(getText(playing ? R.string.pause : R.string.play));

        Integer rating = state == null ? null : state.rating;
        boolean disliked = Integer.valueOf(1).equals(rating);
        boolean liked = Integer.valueOf(5).equals(rating);
        boolean starRated = rating != null && rating >= 2 && rating <= 4;
        boolean shuffled = state != null && Boolean.TRUE.equals(state.shuffle);
        dislikeButton.setSelected(disliked);
        ratingButton.setSelected(starRated);
        likeButton.setSelected(liked);
        shuffleButton.setSelected(shuffled);
        ratingButton.setText(rating == null
                ? getText(R.string.rating_unknown)
                : getString(R.string.rating_value, rating));
        dislikeButton.setContentDescription(getText(disliked
                ? R.string.remove_dislike : R.string.dislike));
        likeButton.setContentDescription(getText(liked ? R.string.remove_like : R.string.like));
        shuffleButton.setContentDescription(getText(shuffled
                ? R.string.shuffle_disable : R.string.shuffle_enable));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ratingButton.setStateDescription(rating == null ? null
                    : getString(R.string.rating_value, rating));
        }
    }

    private int calculatedPositionSeconds() {
        long position = anchorPositionSeconds;
        if (shouldAdvanceProgress()) {
            position += Math.max(0L, SystemClock.elapsedRealtime() - anchorRealtimeMilliseconds)
                    / 1_000L;
        }
        if (durationSeconds > 0) position = Math.min(position, durationSeconds);
        return position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(position, 0L);
    }

    private void setPositionAnchor(int positionSeconds, long realtimeMilliseconds) {
        anchorPositionSeconds = Math.max(positionSeconds, 0);
        anchorRealtimeMilliseconds = realtimeMilliseconds;
    }

    private void freezeProgress() {
        setPositionAnchor(calculatedPositionSeconds(), SystemClock.elapsedRealtime());
        anchorAdvancing = false;
        restartProgressTicker();
    }

    private boolean shouldAdvanceProgress() {
        return activityStarted
                && isConnectedStatus(status)
                && state != null
                && anchorAdvancing
                && "playing".equals(state.playbackState);
    }

    private void restartProgressTicker() {
        uiHandler.removeCallbacks(progressTicker);
        if (shouldAdvanceProgress()) uiHandler.postDelayed(progressTicker, 1_000L);
    }

    private void showRatingDialog() {
        if (state == null) return;
        int selected = state.rating == null ? 0 : state.rating;
        new AlertDialog.Builder(this)
                .setTitle(R.string.rating_dialog_title)
                .setSingleChoiceItems(R.array.rating_options, selected, (dialog, which) -> {
                    hideError();
                    if (ensureServiceAvailable()) controller.setRating(which);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openPlayerDevices() {
        startActivity(new Intent(this, PlayerDevicesActivity.class));
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.main_navigation);
        menu.setOnMenuItemClickListener(item -> {
            haptic(anchor);
            if (item.getItemId() == R.id.menu_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.menu_about) {
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private boolean ensureServiceAvailable() {
        if (controller != null) return true;
        showError(R.string.connection_service_error);
        return false;
    }

    private CharSequence valueOrFallback(String value, int fallbackResource) {
        return value == null || value.trim().isEmpty() ? getText(fallbackResource) : value;
    }

    private static boolean isConnectedStatus(RemoteClientController.Status value) {
        return value == RemoteClientController.Status.CONNECTED
                || value == RemoteClientController.Status.CONNECTED_DIRECT;
    }

    private static void setControlEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.34f);
    }

    private static void setOptionalText(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setText(null);
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    private void showError(int stringResource) {
        errorMessage.setText(stringResource);
        errorMessage.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorMessage.setText(null);
        errorMessage.setVisibility(View.GONE);
    }

    private static void haptic(View view) {
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK);
    }
}
