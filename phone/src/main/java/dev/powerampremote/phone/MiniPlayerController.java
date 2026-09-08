package dev.powerampremote.phone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared listener and renderer for the fixed mini-player used by non-Player main tabs. */
final class MiniPlayerController implements PhoneConnectionService.Listener {
    private final Activity activity;
    private final View root;
    private final View body;
    private final ImageView artworkView;
    private final TextView titleView;
    private final TextView artistView;
    private final ImageButton playPauseButton;
    private final MiniPlayerPresentation presentation = new MiniPlayerPresentation();
    private final int placeholderPadding;

    private PhoneConnectionService.LocalBinder binder;
    private MiniPlayerPresentation.Model model = presentation.model();
    private Bitmap artwork;

    MiniPlayerController(Activity activity) {
        this.activity = activity;
        root = activity.findViewById(R.id.mini_player);
        body = root.findViewById(R.id.mini_player_body);
        artworkView = root.findViewById(R.id.mini_player_artwork);
        titleView = root.findViewById(R.id.mini_player_title);
        artistView = root.findViewById(R.id.mini_player_artist);
        playPauseButton = root.findViewById(R.id.mini_player_play_pause);
        placeholderPadding = Math.round(9f * activity.getResources().getDisplayMetrics().density);

        body.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            BottomNavigation.open(activity, BottomNavigation.Tab.PLAYER);
        });
        playPauseButton.setOnClickListener(view -> {
            PhoneConnectionService.LocalBinder activeBinder = binder;
            if (activeBinder == null || !model.controlsEnabled) return;
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (model.playing) activeBinder.pause();
            else activeBinder.play();
        });
        render();
    }

    void attach(PhoneConnectionService.LocalBinder binder) {
        detach();
        if (binder == null) return;
        // Every bind starts only from the service replay. This clears a stale Activity copy after
        // Forget or a Server change, while retained disconnected snapshots are replayed normally.
        artwork = null;
        model = presentation.reset();
        render();
        this.binder = binder;
        binder.addListener(this);
    }

    void detach() {
        PhoneConnectionService.LocalBinder attached = binder;
        binder = null;
        if (attached != null) attached.removeListener(this);
        model = presentation.updateConnection(false);
        render();
    }

    void onServiceDisconnected() {
        binder = null;
        model = presentation.updateConnection(false);
        render();
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status status,
            long retryDelayMilliseconds
    ) {
        model = presentation.updateConnection(
                status == RemoteClientController.Status.CONNECTED
                        || status == RemoteClientController.Status.CONNECTED_DIRECT
        );
        render();
    }

    @Override
    public void onStateChanged(RemoteState state, long receivedRealtimeMilliseconds) {
        model = presentation.updateState(state);
        if (!model.artworkVisible) artwork = null;
        render();
    }

    @Override
    public void onArtworkChanged(Bitmap artwork) {
        model = presentation.updateArtwork(artwork != null);
        if (artwork != null && model.artworkVisible) this.artwork = artwork;
        else if (!model.artworkVisible) this.artwork = null;
        render();
    }

    @Override public void onPairingFailed(RemoteClientController.PairingError error) { }
    @Override public void onPairingSucceeded(String serviceName) { }
    @Override public void onCommandError(boolean authenticationError) { }
    @Override public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) { }

    private void render() {
        root.setVisibility(model.visible ? View.VISIBLE : View.GONE);
        if (!model.visible) return;

        String title = textOrFallback(model.title, R.string.unknown_title);
        String artist = textOrFallback(model.artist, R.string.unknown_artist);
        titleView.setText(title);
        artistView.setText(artist);
        body.setContentDescription(activity.getString(
                R.string.mini_player_open_description,
                title,
                artist
        ));

        if (model.artworkVisible && artwork != null) {
            artworkView.setPadding(0, 0, 0, 0);
            artworkView.setImageBitmap(artwork);
        } else {
            artworkView.setPadding(
                    placeholderPadding,
                    placeholderPadding,
                    placeholderPadding,
                    placeholderPadding
            );
            artworkView.setImageResource(R.drawable.ic_album_placeholder);
        }

        playPauseButton.setImageResource(model.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setEnabled(model.controlsEnabled);
        playPauseButton.setAlpha(model.controlsEnabled ? 1f : 0.45f);
        int action = model.playing ? R.string.pause : R.string.play;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            playPauseButton.setContentDescription(activity.getString(action));
            playPauseButton.setStateDescription(activity.getString(
                    model.controlsEnabled
                            ? model.playing
                                    ? R.string.playback_state_playing
                                    : R.string.playback_state_paused
                            : R.string.mini_player_controls_unavailable
            ));
        } else {
            playPauseButton.setContentDescription(model.controlsEnabled
                    ? activity.getString(action)
                    : activity.getString(
                            R.string.mini_player_action_unavailable,
                            activity.getString(action)
                    ));
        }
    }

    private String textOrFallback(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? activity.getString(fallback) : value;
    }
}
