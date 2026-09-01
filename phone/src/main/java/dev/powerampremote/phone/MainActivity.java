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
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
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
    private static final long NAVIGATION_CONFIRMATION_TIMEOUT_MILLISECONDS = 2_200L;
    private static final long PLAY_PAUSE_CONFIRMATION_TIMEOUT_MILLISECONDS = 1_200L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ArtworkThemeRequestGate artworkThemeRequestGate =
            new ArtworkThemeRequestGate();
    private final ArtworkPaletteRepository artworkPaletteRepository =
            ArtworkPaletteRepository.get();
    private final ArtworkNavigationCoordinator artworkNavigationCoordinator =
            new ArtworkNavigationCoordinator();
    private final ConnectionIndicatorPolicy.Tracker connectionIndicatorTracker =
            new ConnectionIndicatorPolicy.Tracker();
    private final ControlMotionPolicy.Binary playPauseMotionPolicy =
            new ControlMotionPolicy.Binary();
    private final ControlMotionPolicy.Like likeMotionPolicy =
            new ControlMotionPolicy.Like();
    private final ControlMotionPolicy.Like dislikeMotionPolicy =
            new ControlMotionPolicy.Like();
    private final ControlMotionPolicy.Binary shuffleMotionPolicy =
            new ControlMotionPolicy.Binary();
    private final PlaybackProgressCoordinator playbackProgressCoordinator =
            new PlaybackProgressCoordinator();
    private final PlaybackProgressCoordinator.DisplayedSecondTracker elapsedSecondTracker =
            new PlaybackProgressCoordinator.DisplayedSecondTracker();

    private ArtworkThemeBackgroundView artworkThemeBackground;
    private ArtworkTransitionFrameLayout artworkTransition;
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
    private Button playerDevicesButton;
    private ImageButton previousButton;
    private MotionImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton dislikeButton;
    private Button ratingButton;
    private MotionImageButton likeButton;
    private MotionImageButton shuffleButton;
    private TextView errorMessage;
    private RemoteMetadataFormatter metadataFormatter;
    private MetadataChipDrawableFactory metadataChipDrawableFactory;
    private MetadataChipStylePolicy.Style renderedCodecChipStyle;
    private MetadataChipStylePolicy.Style renderedBitDepthChipStyle;
    private MetadataChipStylePolicy.Style renderedSampleRateChipStyle;
    private MetadataChipStylePolicy.Style renderedBitrateChipStyle;
    private ControlMotionDrawables.Nudge previousGlyph;
    private ControlMotionDrawables.PlayPause playPauseGlyph;
    private ControlMotionDrawables.Nudge nextGlyph;
    private ControlMotionDrawables.Pulse dislikeGlyph;
    private ControlMotionDrawables.Pulse likeGlyph;
    private ControlMotionDrawables.Shuffle shuffleGlyph;

    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status status = RemoteClientController.Status.SEARCHING;
    private RemoteState state;
    private boolean activityStarted;
    private boolean bindingRequested;
    private boolean openedDevicesForMissingPairing;
    private boolean draggingVolume;
    private int durationSeconds;
    private String trackIdentity;
    private boolean progressSnapshotMustApplyImmediately = true;
    private int renderedDurationSeconds = Integer.MIN_VALUE;
    private Integer pendingVolume;
    private long pendingVolumeExpiresRealtimeMilliseconds;
    private String artworkThemeIdentity;
    private String artworkServerIdentity;
    private boolean artworkServerIdentityInitialized;
    private String artworkPaletteKey;
    private ArtworkThemeRequestGate.Request artworkThemeRequest;
    private Runnable pendingArtworkFallback;
    private ArtworkNavigationCoordinator.ArtworkRequest artworkDisplayRequest;
    private Runnable pendingArtworkDisplayFallback;
    private Runnable pendingNavigationRecovery;
    private Boolean pendingPlayPauseTarget;
    private long pendingPlayPauseRequestedAfterRevision;
    private long pendingPlayPauseGeneration;
    private Runnable pendingPlayPauseRecovery;
    private boolean replayingServiceState;
    private boolean artworkPresentationInitialized;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showError(R.string.connection_service_error);
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            PlayerDeviceSnapshot deviceSnapshot = controller.playerDeviceSnapshot();
            String serverIdentity = deviceSnapshot.serverId;
            renderConnectionIndicator(deviceSnapshot.runtimeStatus);
            updateArtworkServerIdentity(serverIdentity);
            initializeArtworkPresentation(serverIdentity);
            replayingServiceState = true;
            try {
                controller.addListener(MainActivity.this);
            } finally {
                replayingServiceState = false;
            }
            if (!controller.hasPairing() && !openedDevicesForMissingPairing) {
                openedDevicesForMissingPairing = true;
                uiHandler.post(MainActivity.this::openPlayerDevices);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            freezeProgress();
            rollbackPendingPlayPause(true);
            cancelPendingArtworkDisplayFallback();
            abortArtworkNavigation();
            controller = null;
            status = RemoteClientController.Status.ERROR;
            renderConnectionIndicator(status);
            renderControls();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            rollbackPendingPlayPause(false);
            controller = null;
            status = RemoteClientController.Status.ERROR;
            renderConnectionIndicator(status);
            showError(R.string.connection_service_error);
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            renderProgress();
            restartProgressTicker();
        }
    };

    private final Choreographer.FrameCallback progressFrameCallback = frameTimeNanos -> {
        renderProgress();
        restartProgressTicker();
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
        metadataChipDrawableFactory = new MetadataChipDrawableFactory(this);
        bindViews();
        renderConnectionIndicator(status);
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
        stopProgressTicker();
        rollbackPendingPlayPause(false);
        previousGlyph.stopMotion();
        playPauseButton.stopImageMotion();
        nextGlyph.stopMotion();
        dislikeGlyph.stopMotion();
        likeButton.stopImageMotion();
        shuffleButton.stopImageMotion();
        cancelPendingArtworkFallback();
        cancelPendingArtworkDisplayFallback();
        cancelPendingNavigationRecovery();
        artworkNavigationCoordinator.abortNavigation();
        artworkTransition.onHostStop();
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
        artworkNavigationCoordinator.reset();
        stopProgressTicker();
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
        artworkTransition = findViewById(R.id.album_art_container);
        artworkTransition.setClipToOutline(true);
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
        playerDevicesButton = findViewById(R.id.player_devices_button);
        previousButton = findViewById(R.id.previous_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        nextButton = findViewById(R.id.next_button);
        dislikeButton = findViewById(R.id.dislike_button);
        ratingButton = findViewById(R.id.rating_button);
        likeButton = findViewById(R.id.like_button);
        shuffleButton = findViewById(R.id.shuffle_button);
        errorMessage = findViewById(R.id.error_message);

        float density = getResources().getDisplayMetrics().density;
        previousGlyph = new ControlMotionDrawables.Nudge(previousButton.getDrawable(), -1);
        previousButton.setImageDrawable(previousGlyph);
        playPauseGlyph = new ControlMotionDrawables.PlayPause(density);
        playPauseButton.setImageDrawable(playPauseGlyph);
        nextGlyph = new ControlMotionDrawables.Nudge(nextButton.getDrawable(), 1);
        nextButton.setImageDrawable(nextGlyph);
        dislikeGlyph = new ControlMotionDrawables.Pulse(dislikeButton.getDrawable());
        dislikeButton.setImageDrawable(dislikeGlyph);
        likeGlyph = new ControlMotionDrawables.Pulse(likeButton.getDrawable());
        likeButton.setImageDrawable(likeGlyph);
        shuffleGlyph = new ControlMotionDrawables.Shuffle(density);
        shuffleButton.setImageDrawable(shuffleGlyph);
    }

    private void configureControls() {
        artworkTransition.setGestureListener(
                new ArtworkTransitionFrameLayout.GestureListener() {
                    @Override
                    public boolean isNavigationAvailable() {
                        return isTrackNavigationAvailable();
                    }

                    @Override
                    public ArtworkPresentationStore.Entry findCachedNeighbor(
                            ArtworkNavigationCoordinator.Direction direction
                    ) {
                        return findCachedArtworkNeighbor(direction);
                    }

                    @Override
                    public boolean onSwipeCommitted(
                            ArtworkNavigationCoordinator.Direction direction,
                            String previewContentIdentity
                    ) {
                        return requestNavigation(
                                direction,
                                ArtworkNavigationCoordinator.Source.SWIPE,
                                previewContentIdentity
                        );
                    }
                }
        );
        findViewById(R.id.main_menu_button).setOnClickListener(view -> {
            haptic(view);
            showMainMenu(view);
        });
        playerDevicesButton.setOnClickListener(view -> {
            haptic(view);
            openPlayerDevices();
        });
        previousButton.setOnClickListener(view -> {
            haptic(view);
            previousGlyph.nudge(animationsEnabled());
            requestNavigation(
                    ArtworkNavigationCoordinator.Direction.PREVIOUS,
                    ArtworkNavigationCoordinator.Source.BUTTON
            );
        });
        playPauseButton.setOnClickListener(view -> {
            haptic(view);
            hideError();
            if (!ensureServiceAvailable()) return;
            boolean targetPlaying = state == null || !"playing".equals(state.playbackState);
            beginPendingPlayPause(targetPlaying);
            if (targetPlaying) controller.play();
            else controller.pause();
        });
        nextButton.setOnClickListener(view -> {
            haptic(view);
            nextGlyph.nudge(animationsEnabled());
            requestNavigation(
                    ArtworkNavigationCoordinator.Direction.NEXT,
                    ArtworkNavigationCoordinator.Source.BUTTON
            );
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
                if (fromUser) {
                    playbackProgressCoordinator.updateDragPosition(progress);
                    int displayedSeconds = PlaybackProgressCoordinator.secondsFloor(progress);
                    if (elapsedSecondTracker.shouldUpdate(progress)) {
                        elapsedTime.setText(TimeFormatter.formatSeconds(displayedSeconds));
                        updateSeekStateDescription(displayedSeconds);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                playbackProgressCoordinator.startDragging(seekBar.getProgress());
                elapsedSecondTracker.reset();
                stopProgressTicker();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long now = SystemClock.elapsedRealtime();
                int requestedPosition = playbackProgressCoordinator.commitSeek(
                        seekBar.getProgress(),
                        now,
                        isConfirmedPlaybackAdvancing()
                );
                haptic(seekBar);
                hideError();
                if (ensureServiceAvailable()) {
                    controller.seek(requestedPosition);
                } else {
                    playbackProgressCoordinator.onCommandFailure(now);
                }
                renderProgress();
                restartProgressTicker();
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
        boolean wasConnected = isConnectedStatus(status);
        boolean nowConnected = isConnectedStatus(newStatus);
        if (wasConnected && !nowConnected) {
            freezeProgress();
            rollbackPendingPlayPause(true);
            cancelPendingArtworkDisplayFallback();
            ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
            abortArtworkNavigation();
        } else if (!wasConnected && nowConnected) {
            progressSnapshotMustApplyImmediately = true;
        }
        status = newStatus;
        renderConnectionIndicator(newStatus);
        renderProgress();
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
        RemoteState previousState = state;
        String nextTrackIdentity = newState.trackIdentity();
        boolean trackChanged = trackIdentity != null && !trackIdentity.equals(nextTrackIdentity);
        boolean shuffleChanged = previousState != null
                && !Objects.equals(previousState.shuffle, newState.shuffle);
        trackIdentity = nextTrackIdentity;
        state = newState;
        reconcilePendingPlayPause(newState);
        if (isConnectedStatus(status)) updateArtworkThemeRequest(newState);
        ArtworkNavigationCoordinator.StateUpdate artworkUpdate =
                artworkNavigationCoordinator.confirmState(
                        artworkTrackIdentity(newState),
                        artworkDisplayKey(newState),
                        replayingServiceState,
                        ArtworkTransitionFrameLayout.animationsEnabled()
        );
        artworkDisplayRequest = artworkUpdate.artworkRequest;
        if (artworkUpdate.changed) {
            cancelPendingArtworkDisplayFallback();
            handleConfirmedArtworkUpdate(artworkUpdate);
            applyCachedConfirmedArtwork(artworkDisplayRequest);
            if (artworkNavigationCoordinator.needsArtworkFallback(artworkDisplayRequest)) {
                scheduleArtworkDisplayFallback(artworkDisplayRequest);
            }
        }
        if (shuffleChanged || !newState.hasTrack) {
            ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
            if (!newState.hasTrack) artworkTransition.rejectPreview();
        }
        durationSeconds = newState.durationSeconds == null ? 0 : newState.durationSeconds;
        long durationMilliseconds = durationMilliseconds();
        if (trackChanged || !newState.hasTrack) {
            playbackProgressCoordinator.onTrackChanged(durationMilliseconds);
            elapsedSecondTracker.reset();
            renderedDurationSeconds = Integer.MIN_VALUE;
            progressSnapshotMustApplyImmediately = true;
        } else {
            playbackProgressCoordinator.updateDuration(durationMilliseconds);
        }
        renderPlayer();
        if (trackChanged || !newState.hasTrack) renderProgress();
        renderVolume();
        renderControls();
    }

    @Override
    public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) {
        if (snapshot == null) return;
        long now = SystemClock.elapsedRealtime();
        playbackProgressCoordinator.onSnapshot(
                snapshot,
                now,
                durationMilliseconds(),
                progressSnapshotMustApplyImmediately || replayingServiceState,
                animationsEnabled()
        );
        progressSnapshotMustApplyImmediately = false;
        if (!playbackProgressCoordinator.isDragging()) renderProgress();
        restartProgressTicker();
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        ArtworkNavigationCoordinator.ArtworkRequest request = artworkDisplayRequest;
        if (artwork == null) {
            ArtworkNavigationCoordinator.Display display =
                    artworkNavigationCoordinator.onArtworkCleared(request);
            boolean needsFallback = display.mode
                    == ArtworkNavigationCoordinator.DisplayMode.KEEP_CURRENT
                    && artworkNavigationCoordinator.needsArtworkFallback(request);
            if (needsFallback) {
                scheduleArtworkDisplayFallback(request);
                handleMissingArtworkPalette();
            }
            return;
        }

        String deliveredArtworkIdentity = artworkDisplayKey(state);
        applyConfirmedArtwork(request, artwork, deliveredArtworkIdentity);
    }

    private void handleConfirmedArtworkUpdate(
            ArtworkNavigationCoordinator.StateUpdate update
    ) {
        ArtworkNavigationCoordinator.ArtworkRequest request = update.artworkRequest;
        if (request == null) return;

        boolean ambiguousNavigation = request.direction
                != ArtworkNavigationCoordinator.Direction.NEUTRAL
                && request.artworkIdentity != null
                && !request.relationReliable;
        boolean externalTrackChange = update.trackChanged
                && request.direction == ArtworkNavigationCoordinator.Direction.NEUTRAL
                && !replayingServiceState;
        if (update.previewMismatch || ambiguousNavigation || externalTrackChange) {
            ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
        }
        if (update.previewMismatch) artworkTransition.rejectPreview();
        if (update.navigationRelationReady) {
            ArtworkPresentationStore.confirmNavigation(
                    artworkServerIdentity,
                    request.originArtworkIdentity,
                    request.direction,
                    request.artworkIdentity
            );
        }
    }

    private void applyCachedConfirmedArtwork(
            ArtworkNavigationCoordinator.ArtworkRequest request
    ) {
        if (request == null || request.artworkIdentity == null) return;
        ArtworkPresentationStore.Entry cached = ArtworkPresentationStore.getArtwork(
                artworkServerIdentity,
                request.artworkIdentity
        );
        if (cached != null && cached.artwork != null) {
            applyConfirmedArtwork(request, cached.artwork, cached.contentIdentity);
        }
    }

    private void applyConfirmedArtwork(
            ArtworkNavigationCoordinator.ArtworkRequest request,
            Bitmap artwork,
            String deliveredArtworkIdentity
    ) {
        ArtworkNavigationCoordinator.Display display =
                artworkNavigationCoordinator.onArtworkReady(
                        request,
                        deliveredArtworkIdentity,
                        ArtworkTransitionFrameLayout.animationsEnabled()
                );
        if (display.mode == ArtworkNavigationCoordinator.DisplayMode.IGNORE) return;
        cancelPendingArtworkDisplayFallback();
        if (!artworkNavigationCoordinator.hasPendingNavigation()) {
            cancelPendingNavigationRecovery();
        }
        if (display.mode == ArtworkNavigationCoordinator.DisplayMode.UPDATE) {
            artworkTransition.updateContent(artwork, deliveredArtworkIdentity);
        } else {
            artworkTransition.showContent(
                    artwork,
                    deliveredArtworkIdentity,
                    display.direction,
                    display.mode == ArtworkNavigationCoordinator.DisplayMode.TRANSITION
            );
        }
        ArtworkPresentationStore.put(
                artworkServerIdentity,
                deliveredArtworkIdentity,
                artwork
        );
        requestArtworkPalette(artwork);
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
            scheduleArtworkFallback(artworkThemeRequest, null);
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
        ArtworkPresentationStore.retainOnly(nextServerIdentity);
        if (changed) {
            updateArtworkThemeRequest(null);
            artworkPresentationInitialized = false;
            artworkNavigationCoordinator.reset();
            artworkDisplayRequest = null;
            cancelPendingArtworkDisplayFallback();
            cancelPendingNavigationRecovery();
        }
    }

    private void initializeArtworkPresentation(String serverIdentity) {
        if (artworkPresentationInitialized) return;
        ArtworkPresentationStore.retainOnly(serverIdentity);
        ArtworkPresentationStore.Entry entry = ArtworkPresentationStore.get(serverIdentity);
        if (entry == null) {
            artworkTransition.setInitialContent(
                    null,
                    "startup-placeholder\u0000" + String.valueOf(serverIdentity)
            );
        } else {
            artworkTransition.setInitialContent(entry.artwork, entry.contentIdentity);
            artworkNavigationCoordinator.seedDisplayedContent(entry.contentIdentity);
        }
        artworkPresentationInitialized = true;
    }

    private void handleMissingArtworkPalette() {
        cancelPendingArtworkFallback();
        ArtworkThemeRequestGate.Request request = artworkThemeRequest;
        String paletteKey = artworkPaletteKey;
        if (paletteKey == null) {
            scheduleArtworkFallback(request, null);
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

    private void scheduleArtworkDisplayFallback(
            ArtworkNavigationCoordinator.ArtworkRequest request
    ) {
        if (request == null || !artworkNavigationCoordinator.accepts(request)) return;
        cancelPendingArtworkDisplayFallback();
        pendingArtworkDisplayFallback = () -> {
            pendingArtworkDisplayFallback = null;
            String placeholderIdentity = "placeholder\u0000" + request.trackIdentity;
            ArtworkNavigationCoordinator.Display display =
                    artworkNavigationCoordinator.onArtworkUnavailable(
                            request,
                            placeholderIdentity,
                            ArtworkTransitionFrameLayout.animationsEnabled()
                    );
            if (display.mode == ArtworkNavigationCoordinator.DisplayMode.IGNORE) return;
            ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
            artworkTransition.rejectPreview();
            if (!artworkNavigationCoordinator.hasPendingNavigation()) {
                cancelPendingNavigationRecovery();
            }
            artworkTransition.showContent(
                    null,
                    placeholderIdentity,
                    display.direction,
                    display.mode == ArtworkNavigationCoordinator.DisplayMode.TRANSITION
            );
            ArtworkPresentationStore.put(
                    artworkServerIdentity,
                    placeholderIdentity,
                    null
            );
        };
        uiHandler.postDelayed(
                pendingArtworkDisplayFallback,
                ARTWORK_FALLBACK_DELAY_MILLISECONDS
        );
    }

    private void cancelPendingArtworkDisplayFallback() {
        Runnable pending = pendingArtworkDisplayFallback;
        pendingArtworkDisplayFallback = null;
        if (pending != null) uiHandler.removeCallbacks(pending);
    }

    private void scheduleNavigationRecovery(long navigationGeneration) {
        cancelPendingNavigationRecovery();
        pendingNavigationRecovery = () -> {
            pendingNavigationRecovery = null;
            if (artworkNavigationCoordinator.abortNavigation(navigationGeneration)) {
                ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
                artworkTransition.restoreConfirmedContent(
                        ArtworkTransitionFrameLayout.animationsEnabled()
                );
            }
        };
        uiHandler.postDelayed(
                pendingNavigationRecovery,
                NAVIGATION_CONFIRMATION_TIMEOUT_MILLISECONDS
        );
    }

    private void cancelPendingNavigationRecovery() {
        Runnable pending = pendingNavigationRecovery;
        pendingNavigationRecovery = null;
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
        rollbackPendingPlayPause(true);
        if (artworkNavigationCoordinator.hasPendingNavigation()) {
            cancelPendingNavigationRecovery();
            abortArtworkNavigation();
        }
        if (playbackProgressCoordinator.hasPendingSeek()) {
            playbackProgressCoordinator.onCommandFailure(SystemClock.elapsedRealtime());
            renderProgress();
            restartProgressTicker();
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
        String codec = metadataFormatter.codec(state);
        String bitDepth = metadataFormatter.bitDepth(state);
        String sampleRate = metadataFormatter.sampleRate(state);
        String bitrate = metadataFormatter.bitrate(state);
        setOptionalText(codecChip, codec);
        setOptionalText(bitDepthChip, bitDepth);
        setOptionalText(sampleRateChip, sampleRate);
        setOptionalText(bitrateChip, bitrate);
        renderedCodecChipStyle = applyMetadataChipStyle(
                codecChip,
                renderedCodecChipStyle,
                codec == null ? null : MetadataChipStylePolicy.codec(
                        state.fileTypeName,
                        state.codec
                )
        );
        renderedBitDepthChipStyle = applyMetadataChipStyle(
                bitDepthChip,
                renderedBitDepthChipStyle,
                bitDepth == null ? null : MetadataChipStylePolicy.bitDepth(
                        state.bitsPerSample
                )
        );
        renderedSampleRateChipStyle = applyMetadataChipStyle(
                sampleRateChip,
                renderedSampleRateChipStyle,
                sampleRate == null ? null : MetadataChipStylePolicy.sampleRate(
                        state.sampleRate
                )
        );
        renderedBitrateChipStyle = applyMetadataChipStyle(
                bitrateChip,
                renderedBitrateChipStyle,
                bitrate == null ? null : MetadataChipStylePolicy.bitrate(state.bitRate)
        );
        setOptionalText(sourceInfo, metadataFormatter.source(state));
    }

    private MetadataChipStylePolicy.Style applyMetadataChipStyle(
            TextView chip,
            MetadataChipStylePolicy.Style renderedStyle,
            MetadataChipStylePolicy.Style nextStyle
    ) {
        if (nextStyle == null || nextStyle == renderedStyle) return renderedStyle;
        chip.setBackground(metadataChipDrawableFactory.background(nextStyle));
        chip.setTextColor(metadataChipDrawableFactory.textColor(nextStyle));
        return nextStyle;
    }

    private void renderConnectionIndicator(RemoteClientController.Status connectionStatus) {
        ConnectionIndicatorPolicy.PresentationState presentation =
                connectionIndicatorTracker.update(connectionStatus);
        if (presentation == null) return;

        int textResource;
        int descriptionResource;
        int dotResource;
        switch (presentation) {
            case LAN:
                textResource = R.string.connection_indicator_lan;
                descriptionResource = R.string.connection_indicator_description_lan;
                dotResource = R.drawable.connection_indicator_dot_connected;
                break;
            case WI_FI_DIRECT:
                textResource = R.string.connection_indicator_wifi_direct;
                descriptionResource = R.string.connection_indicator_description_wifi_direct;
                dotResource = R.drawable.connection_indicator_dot_connected;
                break;
            case CONNECTING:
                textResource = R.string.connection_indicator_connecting;
                descriptionResource = R.string.connection_indicator_description_connecting;
                dotResource = R.drawable.connection_indicator_dot_connecting;
                break;
            case DISCONNECTED:
            default:
                textResource = R.string.connection_indicator_disconnected;
                descriptionResource = R.string.connection_indicator_description_disconnected;
                dotResource = R.drawable.connection_indicator_dot_disconnected;
                break;
        }
        playerDevicesButton.setText(textResource);
        playerDevicesButton.setContentDescription(getText(descriptionResource));
        playerDevicesButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                dotResource,
                0,
                0,
                0
        );
    }

    private void renderProgress() {
        if (playbackProgressCoordinator.isDragging()) return;
        long now = SystemClock.elapsedRealtime();
        long positionMilliseconds = playbackProgressCoordinator.positionMillisecondsAt(now);
        int maximum = seekBarMaximumMilliseconds();
        int progress = (int) Math.min(positionMilliseconds, maximum);
        if (trackSeek.getMax() != maximum) trackSeek.setMax(maximum);
        if (trackSeek.getProgress() != progress) trackSeek.setProgress(progress);

        int displayedSeconds = PlaybackProgressCoordinator.secondsFloor(positionMilliseconds);
        boolean elapsedChanged = elapsedSecondTracker.shouldUpdate(positionMilliseconds);
        boolean durationChanged = renderedDurationSeconds != durationSeconds;
        if (elapsedChanged) elapsedTime.setText(TimeFormatter.formatSeconds(displayedSeconds));
        if (durationChanged) {
            renderedDurationSeconds = durationSeconds;
            durationTime.setText(TimeFormatter.formatSeconds(durationSeconds));
        }
        if (elapsedChanged || durationChanged) updateSeekStateDescription(displayedSeconds);
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
        if (state != null) {
            applyBinaryMotion(
                    playPauseGlyph,
                    playPauseMotionPolicy.update(
                            playing,
                            replayingServiceState,
                            activityStarted,
                            animationsEnabled(),
                            playPauseGlyph.progress()
                    )
            );
            ControlMotionPolicy.Mode likeMotion = likeMotionPolicy.update(
                    liked,
                    replayingServiceState,
                    activityStarted,
                    animationsEnabled()
            );
            if (likeMotion == ControlMotionPolicy.Mode.PULSE) {
                likeGlyph.pulse(animationsEnabled());
            } else if (likeMotion == ControlMotionPolicy.Mode.RESET) {
                likeGlyph.reset();
            }
            ControlMotionPolicy.Mode dislikeMotion = dislikeMotionPolicy.update(
                    disliked,
                    replayingServiceState,
                    activityStarted,
                    animationsEnabled()
            );
            if (dislikeMotion == ControlMotionPolicy.Mode.PULSE) {
                dislikeGlyph.pulse(animationsEnabled());
            } else if (dislikeMotion == ControlMotionPolicy.Mode.RESET) {
                dislikeGlyph.reset();
            }
            applyBinaryMotion(
                    shuffleGlyph,
                    shuffleMotionPolicy.update(
                            shuffled,
                            replayingServiceState,
                            activityStarted,
                            animationsEnabled(),
                            shuffleGlyph.progress()
                    )
            );
        }
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
            playPauseButton.setStateDescription(getText(playing
                    ? R.string.playback_state_playing : R.string.playback_state_paused));
            shuffleButton.setStateDescription(getText(shuffled
                    ? R.string.shuffle_state_on : R.string.shuffle_state_off));
        }
    }

    private void applyBinaryMotion(
            ControlMotionDrawables.BinaryMorphDrawable drawable,
            ControlMotionPolicy.Update update
    ) {
        if (update.mode == ControlMotionPolicy.Mode.IMMEDIATE) {
            drawable.setProgressImmediately(update.targetProgress);
        } else if (update.mode == ControlMotionPolicy.Mode.ANIMATE) {
            drawable.animateTo(update.targetProgress, animationsEnabled());
        }
    }

    private void beginPendingPlayPause(boolean targetPlaying) {
        cancelPendingPlayPauseRecovery();
        pendingPlayPauseTarget = targetPlaying;
        pendingPlayPauseRequestedAfterRevision = state == null
                ? Long.MIN_VALUE : state.revision;
        long generation = ++pendingPlayPauseGeneration;
        playPauseGlyph.animateTo(targetPlaying ? 1f : 0f, animationsEnabled());
        pendingPlayPauseRecovery = () -> {
            if (generation != pendingPlayPauseGeneration
                    || !Objects.equals(pendingPlayPauseTarget, targetPlaying)) {
                return;
            }
            pendingPlayPauseRecovery = null;
            pendingPlayPauseTarget = null;
            playPauseGlyph.animateTo(
                    confirmedPlayPauseProgress(),
                    activityStarted && animationsEnabled()
            );
        };
        uiHandler.postDelayed(
                pendingPlayPauseRecovery,
                PLAY_PAUSE_CONFIRMATION_TIMEOUT_MILLISECONDS
        );
    }

    private void reconcilePendingPlayPause(RemoteState confirmedState) {
        Boolean targetPlaying = pendingPlayPauseTarget;
        if (targetPlaying == null) return;
        boolean confirmedPlaying = "playing".equals(confirmedState.playbackState);
        if (targetPlaying == confirmedPlaying) {
            clearPendingPlayPause();
            playPauseMotionPolicy.update(
                    confirmedPlaying,
                    replayingServiceState,
                    activityStarted,
                    animationsEnabled(),
                    playPauseGlyph.progress()
            );
        } else if (confirmedState.revision > pendingPlayPauseRequestedAfterRevision) {
            rollbackPendingPlayPause(true);
        }
    }

    private void rollbackPendingPlayPause(boolean animate) {
        if (pendingPlayPauseTarget == null && pendingPlayPauseRecovery == null) return;
        clearPendingPlayPause();
        float confirmedProgress = confirmedPlayPauseProgress();
        if (animate && activityStarted) {
            playPauseGlyph.animateTo(confirmedProgress, animationsEnabled());
        } else {
            playPauseGlyph.setProgressImmediately(confirmedProgress);
        }
    }

    private void clearPendingPlayPause() {
        pendingPlayPauseTarget = null;
        pendingPlayPauseGeneration++;
        cancelPendingPlayPauseRecovery();
    }

    private void cancelPendingPlayPauseRecovery() {
        Runnable pending = pendingPlayPauseRecovery;
        pendingPlayPauseRecovery = null;
        if (pending != null) uiHandler.removeCallbacks(pending);
    }

    private float confirmedPlayPauseProgress() {
        return state != null && "playing".equals(state.playbackState) ? 1f : 0f;
    }

    private void freezeProgress() {
        playbackProgressCoordinator.freezeAt(SystemClock.elapsedRealtime());
        progressSnapshotMustApplyImmediately = true;
        renderProgress();
        restartProgressTicker();
    }

    private boolean isConfirmedPlaybackAdvancing() {
        return isConnectedStatus(status)
                && state != null
                && state.hasTrack
                && durationSeconds > 0
                && "playing".equals(state.playbackState);
    }

    private void restartProgressTicker() {
        stopProgressTicker();
        if (!activityStarted) return;
        long now = SystemClock.elapsedRealtime();
        boolean continuous = playbackProgressCoordinator.shouldRunContinuousUpdates(
                true,
                isConnectedStatus(status),
                state != null
                        && state.hasTrack
                        && durationSeconds > 0
                        && "playing".equals(state.playbackState)
        );
        if (continuous) {
            if (animationsEnabled()) {
                Choreographer.getInstance().postFrameCallback(progressFrameCallback);
            } else {
                uiHandler.postDelayed(
                        progressTicker,
                        PlaybackProgressCoordinator.REDUCED_MOTION_TICK_MILLISECONDS
                );
            }
            return;
        }

        long pendingTimeout = playbackProgressCoordinator.pendingTimeoutDelayMilliseconds(now);
        if (pendingTimeout >= 0L) {
            uiHandler.postDelayed(progressTicker, Math.max(1L, pendingTimeout));
        }
    }

    private void stopProgressTicker() {
        uiHandler.removeCallbacks(progressTicker);
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback);
    }

    private long durationMilliseconds() {
        return Math.max(durationSeconds, 0) * 1_000L;
    }

    private int seekBarMaximumMilliseconds() {
        long maximum = Math.max(durationMilliseconds(), 1L);
        return maximum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maximum;
    }

    private void updateSeekStateDescription(int displayedSeconds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        trackSeek.setStateDescription(getString(
                R.string.playback_position_value,
                TimeFormatter.formatSeconds(displayedSeconds),
                TimeFormatter.formatSeconds(durationSeconds)
        ));
    }

    private static boolean animationsEnabled() {
        return ArtworkTransitionFrameLayout.animationsEnabled();
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

    private boolean requestNavigation(
            ArtworkNavigationCoordinator.Direction direction,
            ArtworkNavigationCoordinator.Source source
    ) {
        return requestNavigation(direction, source, null);
    }

    private boolean requestNavigation(
            ArtworkNavigationCoordinator.Direction direction,
            ArtworkNavigationCoordinator.Source source,
            String gesturePreviewIdentity
    ) {
        ArtworkNavigationCoordinator.Navigation navigation =
                artworkNavigationCoordinator.requestNavigation(
                        direction,
                        source,
                        isTrackNavigationAvailable()
                );
        if (!navigation.accepted || controller == null) {
            artworkTransition.restoreConfirmedContent(true);
            return false;
        }
        ArtworkPresentationStore.Entry preview = null;
        if (artworkTransition.hasConfirmedContent(navigation.fromArtworkIdentity)) {
            ArtworkPresentationStore.Entry candidate = ArtworkPresentationStore.getNeighbor(
                    artworkServerIdentity,
                    navigation.fromArtworkIdentity,
                    navigation.direction
            );
            if (candidate != null && (gesturePreviewIdentity == null
                    || Objects.equals(gesturePreviewIdentity, candidate.contentIdentity))) {
                preview = candidate;
            }
        }
        artworkNavigationCoordinator.attachExpectedPreview(
                navigation.generation,
                preview == null ? null : preview.contentIdentity
        );
        hideError();
        artworkTransition.onNavigationRequested(
                navigation.direction,
                preview == null ? null : preview.artwork,
                preview == null ? null : preview.contentIdentity
        );
        scheduleNavigationRecovery(navigation.generation);
        if (navigation.command == ArtworkNavigationCoordinator.Command.PREVIOUS) {
            controller.previous();
        } else if (navigation.command == ArtworkNavigationCoordinator.Command.NEXT) {
            controller.next();
        }
        return true;
    }

    private void abortArtworkNavigation() {
        cancelPendingNavigationRecovery();
        artworkNavigationCoordinator.abortNavigation();
        ArtworkPresentationStore.clearAdjacency(artworkServerIdentity);
        if (artworkTransition != null) {
            artworkTransition.restoreConfirmedContent(
                    ArtworkTransitionFrameLayout.animationsEnabled()
            );
        }
    }

    private ArtworkPresentationStore.Entry findCachedArtworkNeighbor(
            ArtworkNavigationCoordinator.Direction direction
    ) {
        String currentIdentity = artworkDisplayKey(state);
        if (currentIdentity == null
                || !artworkTransition.hasConfirmedContent(currentIdentity)) {
            return null;
        }
        return ArtworkPresentationStore.getNeighbor(
                artworkServerIdentity,
                currentIdentity,
                direction
        );
    }

    private boolean isTrackNavigationAvailable() {
        return controller != null
                && isConnectedStatus(status)
                && state != null
                && state.powerampAvailable
                && state.hasTrack;
    }

    private String artworkDisplayKey(RemoteState remoteState) {
        return remoteState == null ? null : ArtworkPaletteCacheKey.create(
                artworkServerIdentity,
                remoteState.artworkKey()
        );
    }

    private String artworkTrackIdentity(RemoteState remoteState) {
        String remoteIdentity = remoteState == null || !remoteState.hasTrack
                ? "no-track" : remoteState.trackIdentity();
        return String.valueOf(artworkServerIdentity) + '\u0000' + remoteIdentity;
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
