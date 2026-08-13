package dev.powerampremote.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Native phone control surface for the existing Poweramp Remote API v1. */
public final class MainActivity extends Activity implements PhoneConnectionService.Listener {
    private static final int DIRECT_PERMISSION_REQUEST = 700;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 701;
    private static final String STATE_PERMISSION_REQUEST_ATTEMPTED =
            "direct_permission_request_attempted";

    private enum ConnectionAction {
        NONE, PERMISSION, LOCATION_SETTINGS, WIFI_SETTINGS, RETRY_DIRECT, RETRY_LAN
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView connectionStatus;
    private TextView serverIdentity;
    private Button connectionActionButton;
    private View pairingPanel;
    private ProgressBar discoveryProgress;
    private TextView pairingMessage;
    private TextView foundServer;
    private EditText tokenInput;
    private Button pairButton;
    private View playerPanel;
    private ImageView albumArt;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView audioInfo;
    private TextView sourceInfo;
    private SeekBar trackSeek;
    private TextView elapsedTime;
    private TextView durationTime;
    private ImageButton previousButton;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton dislikeButton;
    private Button ratingButton;
    private ImageButton likeButton;
    private ImageButton shuffleButton;
    private TextView errorMessage;
    private Button forgetPairingButton;

    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status status = RemoteClientController.Status.SEARCHING;
    private DiscoveredServer pairingServer;
    private RemoteState state;
    private boolean activityStarted;
    private boolean draggingSeek;
    private int durationSeconds;
    private int anchorPositionSeconds;
    private long anchorRealtimeMilliseconds;
    private String trackIdentity;
    private Integer pendingSeekSeconds;
    private long pendingSeekExpiresRealtimeMilliseconds;
    private ConnectionAction connectionAction = ConnectionAction.NONE;
    private boolean permissionRequestAttempted;
    private boolean permissionRequestInFlight;
    private boolean notificationPermissionInFlight;
    private boolean serviceBindingRequested;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showError(R.string.connection_service_error);
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            renderBoundServiceState();
            controller.addListener(MainActivity.this);
            if (isDirectRecoveryStatus(status)) {
                controller.onDirectPermissionOrSettingsChanged();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controller = null;
            status = RemoteClientController.Status.ERROR;
            connectionStatus.setText(R.string.connection_service_error);
            connectionStatus.setTextColor(getColor(R.color.error));
            renderConnectionAction(status);
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
            if (activityStarted && state != null && "playing".equals(state.playbackState)) {
                uiHandler.postDelayed(this, 1_000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequestAttempted = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PERMISSION_REQUEST_ATTEMPTED, false);
        setContentView(R.layout.activity_main);
        bindViews();
        configureControls();
        showScanningPanel();
        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException exception) {
            showError(R.string.connection_service_error);
        }
        requestNotificationPermission();
        renderPlayer();
        renderControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        serviceBindingRequested = bindService(
                PhoneConnectionService.bindingIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
        if (!serviceBindingRequested) showError(R.string.connection_service_error);
        restartProgressTicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null && isDirectRecoveryStatus(status)) {
            controller.onDirectPermissionOrSettingsChanged();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        uiHandler.removeCallbacks(progressTicker);
        if (controller != null) {
            controller.removeListener(this);
            controller = null;
        }
        if (serviceBindingRequested) {
            unbindService(serviceConnection);
            serviceBindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_REQUEST_ATTEMPTED, permissionRequestAttempted);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == DIRECT_PERMISSION_REQUEST) {
            permissionRequestInFlight = false;
            renderConnectionAction(status);
            if (controller != null) controller.onDirectPermissionOrSettingsChanged();
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            notificationPermissionInFlight = false;
            if (status == RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED) {
                requestDirectPermission();
            }
        }
    }

    private void bindViews() {
        connectionStatus = findViewById(R.id.connection_status);
        serverIdentity = findViewById(R.id.server_identity);
        connectionActionButton = findViewById(R.id.connection_action);
        pairingPanel = findViewById(R.id.pairing_panel);
        discoveryProgress = findViewById(R.id.discovery_progress);
        pairingMessage = findViewById(R.id.pairing_message);
        foundServer = findViewById(R.id.found_server);
        tokenInput = findViewById(R.id.token_input);
        pairButton = findViewById(R.id.pair_button);
        playerPanel = findViewById(R.id.player_panel);
        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        audioInfo = findViewById(R.id.audio_info);
        sourceInfo = findViewById(R.id.source_info);
        trackSeek = findViewById(R.id.track_seek);
        elapsedTime = findViewById(R.id.elapsed_time);
        durationTime = findViewById(R.id.duration_time);
        previousButton = findViewById(R.id.previous_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        nextButton = findViewById(R.id.next_button);
        dislikeButton = findViewById(R.id.dislike_button);
        ratingButton = findViewById(R.id.rating_button);
        likeButton = findViewById(R.id.like_button);
        shuffleButton = findViewById(R.id.shuffle_button);
        errorMessage = findViewById(R.id.error_message);
        forgetPairingButton = findViewById(R.id.forget_pairing_button);
    }

    private void renderBoundServiceState() {
        if (controller == null) return;
        if (controller.hasPairing()) {
            pairingPanel.setVisibility(View.GONE);
            playerPanel.setVisibility(View.VISIBLE);
            forgetPairingButton.setVisibility(View.VISIBLE);
            serverIdentity.setText(getString(
                    R.string.known_server,
                    controller.pairedServiceName()
            ));
        } else if (pairingServer == null) {
            showScanningPanel();
            forgetPairingButton.setVisibility(View.GONE);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionInFlight = true;
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

    private void configureControls() {
        connectionActionButton.setOnClickListener(view -> performConnectionAction());
        pairButton.setOnClickListener(view -> submitPairing());
        tokenInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && pairButton.isEnabled()) {
                submitPairing();
                return true;
            }
            return false;
        });
        previousButton.setOnClickListener(view -> {
            hideError();
            if (ensureServiceAvailable()) controller.previous();
        });
        playPauseButton.setOnClickListener(view -> {
            hideError();
            if (!ensureServiceAvailable()) return;
            if (state != null && "playing".equals(state.playbackState)) controller.pause();
            else controller.play();
        });
        nextButton.setOnClickListener(view -> {
            hideError();
            if (ensureServiceAvailable()) controller.next();
        });
        dislikeButton.setOnClickListener(view -> {
            hideError();
            if (ensureServiceAvailable()) {
                controller.setRating(
                        state != null && Integer.valueOf(1).equals(state.rating) ? 0 : 1
                );
            }
        });
        likeButton.setOnClickListener(view -> {
            hideError();
            if (ensureServiceAvailable()) {
                controller.setRating(
                        state != null && Integer.valueOf(5).equals(state.rating) ? 0 : 5
                );
            }
        });
        shuffleButton.setOnClickListener(view -> {
            hideError();
            if (ensureServiceAvailable()) {
                controller.setShuffle(state == null || !Boolean.TRUE.equals(state.shuffle));
            }
        });
        ratingButton.setOnClickListener(view -> showRatingDialog());
        forgetPairingButton.setOnClickListener(view -> showForgetPairingDialog());
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
                setPositionAnchor(requestedPosition);
                pendingSeekSeconds = requestedPosition;
                pendingSeekExpiresRealtimeMilliseconds =
                        SystemClock.elapsedRealtime() + 2_000L;
                hideError();
                if (ensureServiceAvailable()) controller.seek(requestedPosition);
                renderProgress();
            }
        });
    }

    private void submitPairing() {
        if (pairingServer == null || !ensureServiceAvailable()) return;
        hideError();
        controller.pair(pairingServer, tokenInput.getText().toString());
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status newStatus,
            long retryDelayMilliseconds
    ) {
        if (newStatus == RemoteClientController.Status.SEARCHING
                && controller != null && !controller.hasPairing() && pairingServer == null) {
            showScanningPanel();
        }
        status = newStatus;
        int textResource;
        int colorResource;
        switch (newStatus) {
            case PAIRING:
                textResource = R.string.status_pairing;
                colorResource = R.color.warning;
                break;
            case VERIFYING:
                textResource = R.string.status_verifying;
                colorResource = R.color.warning;
                break;
            case CONNECTING:
                textResource = R.string.status_connecting;
                colorResource = R.color.warning;
                break;
            case CONNECTED:
                textResource = R.string.status_connected;
                colorResource = R.color.accent;
                break;
            case DIRECT_SEARCHING:
                textResource = R.string.status_direct_searching;
                colorResource = R.color.warning;
                break;
            case DIRECT_PERMISSION_REQUIRED:
                textResource = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? R.string.status_direct_permission_nearby
                        : R.string.status_direct_permission_location;
                colorResource = R.color.warning;
                break;
            case DIRECT_LOCATION_REQUIRED:
                textResource = R.string.status_direct_location;
                colorResource = R.color.warning;
                break;
            case DIRECT_WIFI_REQUIRED:
                textResource = R.string.status_direct_wifi;
                colorResource = R.color.warning;
                break;
            case DIRECT_CONNECTING:
                textResource = R.string.status_direct_connecting;
                colorResource = R.color.warning;
                break;
            case CONNECTED_DIRECT:
                textResource = R.string.status_connected_direct;
                colorResource = R.color.accent;
                break;
            case DIRECT_UNSUPPORTED:
                textResource = R.string.status_direct_unsupported;
                colorResource = R.color.error;
                break;
            case DIRECT_ACTION_REQUIRED:
                textResource = R.string.status_direct_action_required;
                colorResource = R.color.error;
                break;
            case RETRYING:
                long seconds = Math.max(1L, (retryDelayMilliseconds + 999L) / 1_000L);
                connectionStatus.setText(getString(R.string.status_retrying, seconds));
                connectionStatus.setTextColor(getColor(R.color.warning));
                renderConnectionAction(newStatus);
                renderControls();
                return;
            case AUTH_REQUIRED:
                textResource = R.string.status_auth_required;
                colorResource = R.color.error;
                break;
            case ERROR:
                textResource = R.string.status_error;
                colorResource = R.color.error;
                break;
            case SEARCHING:
            default:
                textResource = R.string.status_searching;
                colorResource = R.color.warning;
                break;
        }
        connectionStatus.setText(textResource);
        connectionStatus.setTextColor(getColor(colorResource));
        renderConnectionAction(newStatus);
        pairButton.setEnabled(pairingServer != null && newStatus != RemoteClientController.Status.VERIFYING);
        renderControls();
        if (newStatus == RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED
                && !permissionRequestAttempted
                && !permissionRequestInFlight
                && !notificationPermissionInFlight) {
            uiHandler.post(this::requestDirectPermission);
        }
    }

    @Override
    public void onPairingRequired(DiscoveredServer server, boolean tokenRejected) {
        pairingServer = server;
        forgetPairingButton.setVisibility(
                controller != null && controller.hasPairing() ? View.VISIBLE : View.GONE
        );
        playerPanel.setVisibility(View.GONE);
        pairingPanel.setVisibility(View.VISIBLE);
        discoveryProgress.setVisibility(View.GONE);
        pairingMessage.setText(R.string.pairing_instruction);
        foundServer.setText(getString(R.string.pairing_found_server, server.serviceName));
        foundServer.setVisibility(View.VISIBLE);
        tokenInput.setVisibility(View.VISIBLE);
        pairButton.setVisibility(View.VISIBLE);
        pairButton.setEnabled(true);
        serverIdentity.setText(getString(
                R.string.endpoint_value,
                server.serviceName,
                server.addressLabel()
        ));
        if (tokenRejected) {
            tokenInput.setText(null);
            showError(R.string.pairing_unauthorized);
        }
    }

    @Override
    public void onPairingFailed(RemoteClientController.PairingError error) {
        switch (error) {
            case INVALID_TOKEN:
                showError(R.string.pairing_invalid_token);
                break;
            case UNAUTHORIZED:
                showError(R.string.pairing_unauthorized);
                break;
            case STORAGE:
                showError(R.string.pairing_storage_error);
                break;
            case NETWORK:
            default:
                showError(R.string.pairing_network_error);
                break;
        }
        pairButton.setEnabled(pairingServer != null);
    }

    @Override
    public void onPairingSucceeded(String serviceName) {
        pairingPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);
        forgetPairingButton.setVisibility(View.VISIBLE);
        serverIdentity.setText(getString(R.string.known_server, serviceName));
        tokenInput.setText(null);
        hideError();
        Toast.makeText(this, getString(R.string.pairing_success, serviceName),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStateChanged(RemoteState newState) {
        long now = SystemClock.elapsedRealtime();
        String nextTrackIdentity = newState.trackIdentity();
        boolean trackChanged = trackIdentity != null
                && !trackIdentity.equals(nextTrackIdentity);
        trackIdentity = nextTrackIdentity;
        state = newState;
        durationSeconds = newState.durationSeconds == null ? 0 : newState.durationSeconds;
        if (trackChanged) {
            pendingSeekSeconds = null;
            draggingSeek = false;
        }
        if (!draggingSeek && newState.positionSeconds != null) {
            boolean seekConfirmed = pendingSeekSeconds != null
                    && Math.abs(newState.positionSeconds - pendingSeekSeconds) <= 2;
            if (pendingSeekSeconds == null
                    || seekConfirmed
                    || now >= pendingSeekExpiresRealtimeMilliseconds) {
                pendingSeekSeconds = null;
                setPositionAnchor(newState.positionSeconds);
            }
        } else if (!draggingSeek && (trackChanged || !newState.hasTrack)) {
            pendingSeekSeconds = null;
            setPositionAnchor(0);
        }
        playerPanel.setVisibility(View.VISIBLE);
        if (controller != null && controller.hasPairing()) {
            forgetPairingButton.setVisibility(View.VISIBLE);
        }
        renderPlayer();
        renderProgress();
        renderControls();
        restartProgressTicker();
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        if (artwork == null) {
            int padding = getResources().getDimensionPixelSize(R.dimen.album_placeholder_padding);
            albumArt.setPadding(padding, padding, padding, padding);
            albumArt.setImageResource(R.drawable.ic_album_placeholder);
        } else {
            albumArt.setPadding(0, 0, 0, 0);
            albumArt.setImageBitmap(artwork);
        }
    }

    @Override
    public void onCommandError(boolean authenticationError) {
        if (pendingSeekSeconds != null) {
            pendingSeekSeconds = null;
            setPositionAnchor(state == null || state.positionSeconds == null
                    ? 0 : state.positionSeconds);
            renderProgress();
        }
        if (!authenticationError) showError(R.string.command_error);
    }

    private void renderPlayer() {
        if (state == null || !state.hasTrack) {
            trackTitle.setText(R.string.waiting_title);
            trackArtist.setText(R.string.waiting_artist);
            trackAlbum.setText(R.string.waiting_album);
            setOptionalText(audioInfo, null);
            setOptionalText(sourceInfo, null);
            durationSeconds = 0;
            anchorPositionSeconds = 0;
            return;
        }
        trackTitle.setText(valueOrFallback(state.title, R.string.unknown_title));
        trackArtist.setText(valueOrFallback(state.artist, R.string.unknown_artist));
        trackAlbum.setText(valueOrFallback(state.album, R.string.unknown_album));
        setOptionalText(audioInfo, RemoteMetadataFormatter.audio(state));
        setOptionalText(sourceInfo, RemoteMetadataFormatter.source(state));
    }

    private void renderProgress() {
        if (draggingSeek) return;
        int position = calculatedPositionSeconds();
        trackSeek.setMax(Math.max(durationSeconds, 1));
        trackSeek.setProgress(Math.min(position, Math.max(durationSeconds, 1)));
        elapsedTime.setText(TimeFormatter.formatSeconds(position));
        durationTime.setText(TimeFormatter.formatSeconds(durationSeconds));
    }

    private int calculatedPositionSeconds() {
        long position = anchorPositionSeconds;
        if (state != null && "playing".equals(state.playbackState)) {
            position += Math.max(0L, SystemClock.elapsedRealtime() - anchorRealtimeMilliseconds) / 1_000L;
        }
        if (durationSeconds > 0) position = Math.min(position, durationSeconds);
        return position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(position, 0L);
    }

    private void setPositionAnchor(int positionSeconds) {
        anchorPositionSeconds = Math.max(positionSeconds, 0);
        anchorRealtimeMilliseconds = SystemClock.elapsedRealtime();
    }

    private void renderControls() {
        boolean apiReady = (status == RemoteClientController.Status.CONNECTED
                || status == RemoteClientController.Status.CONNECTED_DIRECT)
                && state != null && state.powerampAvailable;
        boolean trackReady = apiReady && state.hasTrack;
        setControlEnabled(previousButton, trackReady);
        setControlEnabled(playPauseButton, apiReady);
        setControlEnabled(nextButton, trackReady);
        setControlEnabled(trackSeek, trackReady && durationSeconds > 0);
        setControlEnabled(dislikeButton, trackReady);
        setControlEnabled(ratingButton, trackReady);
        setControlEnabled(likeButton, trackReady);
        setControlEnabled(shuffleButton, trackReady);

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

    private void restartProgressTicker() {
        uiHandler.removeCallbacks(progressTicker);
        if (activityStarted && state != null && "playing".equals(state.playbackState)) {
            uiHandler.postDelayed(progressTicker, 1_000L);
        }
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

    private void showForgetPairingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_pairing_title)
                .setMessage(R.string.forget_pairing_message)
                .setPositiveButton(R.string.forget_confirm, (dialog, which) -> {
                    if (!ensureServiceAvailable()) return;
                    DiscoveredServer previousPairingServer = pairingServer;
                    pairingServer = null;
                    if (!controller.forgetPairing()) {
                        pairingServer = previousPairingServer;
                        return;
                    }
                    state = null;
                    if (pairingServer == null) {
                        showScanningPanel();
                        serverIdentity.setText(null);
                    }
                    playerPanel.setVisibility(View.GONE);
                    trackIdentity = null;
                    pendingSeekSeconds = null;
                    draggingSeek = false;
                    forgetPairingButton.setVisibility(View.GONE);
                    onArtworkChanged(null);
                    renderPlayer();
                    renderControls();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showScanningPanel() {
        pairingPanel.setVisibility(View.VISIBLE);
        discoveryProgress.setVisibility(View.VISIBLE);
        pairingMessage.setText(R.string.pairing_scanning);
        foundServer.setVisibility(View.GONE);
        tokenInput.setVisibility(View.GONE);
        pairButton.setVisibility(View.GONE);
    }

    private void renderConnectionAction(RemoteClientController.Status currentStatus) {
        int textResource;
        switch (currentStatus) {
            case DIRECT_PERMISSION_REQUIRED:
                connectionAction = ConnectionAction.PERMISSION;
                textResource = R.string.direct_action_permission;
                break;
            case DIRECT_LOCATION_REQUIRED:
                connectionAction = ConnectionAction.LOCATION_SETTINGS;
                textResource = R.string.direct_action_location;
                break;
            case DIRECT_WIFI_REQUIRED:
                connectionAction = ConnectionAction.WIFI_SETTINGS;
                textResource = R.string.direct_action_wifi;
                break;
            case DIRECT_ACTION_REQUIRED:
                connectionAction = ConnectionAction.RETRY_DIRECT;
                textResource = R.string.direct_action_retry;
                break;
            case DIRECT_UNSUPPORTED:
                connectionAction = ConnectionAction.RETRY_LAN;
                textResource = R.string.direct_action_retry_lan;
                break;
            default:
                connectionAction = ConnectionAction.NONE;
                connectionActionButton.setVisibility(View.GONE);
                return;
        }
        connectionActionButton.setText(textResource);
        connectionActionButton.setEnabled(!permissionRequestInFlight);
        connectionActionButton.setVisibility(View.VISIBLE);
    }

    private void performConnectionAction() {
        switch (connectionAction) {
            case PERMISSION:
                requestDirectPermission();
                break;
            case LOCATION_SETTINGS:
                openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                break;
            case WIFI_SETTINGS:
                openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
                break;
            case RETRY_DIRECT:
                if (ensureServiceAvailable()) controller.retryDirectConnection();
                break;
            case RETRY_LAN:
                if (ensureServiceAvailable()) controller.retryLanDiscovery();
                break;
            case NONE:
            default:
                break;
        }
    }

    private void requestDirectPermission() {
        if (permissionRequestInFlight || notificationPermissionInFlight) return;
        String permission = WifiDirectConnectionClient.requiredRuntimePermission();
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            if (controller != null) controller.onDirectPermissionOrSettingsChanged();
            return;
        }
        if (permissionRequestAttempted && !shouldShowRequestPermissionRationale(permission)) {
            Intent settings = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(settings);
            return;
        }
        permissionRequestAttempted = true;
        permissionRequestInFlight = true;
        connectionActionButton.setEnabled(false);
        requestPermissions(
                WifiDirectConnectionClient.requiredRuntimePermissions(),
                DIRECT_PERMISSION_REQUEST
        );
    }

    private void openSystemSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (RuntimeException exception) {
            if (controller != null) controller.retryDirectConnection();
        }
    }

    private static boolean isDirectRecoveryStatus(RemoteClientController.Status value) {
        return value == RemoteClientController.Status.DIRECT_PERMISSION_REQUIRED
                || value == RemoteClientController.Status.DIRECT_LOCATION_REQUIRED
                || value == RemoteClientController.Status.DIRECT_WIFI_REQUIRED
                || value == RemoteClientController.Status.DIRECT_ACTION_REQUIRED;
    }

    private CharSequence valueOrFallback(String value, int fallbackResource) {
        return value == null || value.trim().isEmpty() ? getText(fallbackResource) : value;
    }

    private static void setControlEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
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
}
