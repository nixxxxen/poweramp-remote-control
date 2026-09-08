package dev.powerampremote.phone;

import java.util.Objects;

/** Pure confirmed-snapshot presentation state for the shared mini-player. */
final class MiniPlayerPresentation {
    static final class Model {
        final boolean visible;
        final boolean controlsEnabled;
        final boolean playing;
        final boolean artworkVisible;
        final String trackIdentity;
        final String title;
        final String artist;

        private Model(
                boolean visible,
                boolean controlsEnabled,
                boolean playing,
                boolean artworkVisible,
                String trackIdentity,
                String title,
                String artist
        ) {
            this.visible = visible;
            this.controlsEnabled = controlsEnabled;
            this.playing = playing;
            this.artworkVisible = artworkVisible;
            this.trackIdentity = trackIdentity;
            this.title = title;
            this.artist = artist;
        }
    }

    private RemoteState state;
    private boolean connected;
    private String expectedArtworkIdentity;
    private String displayedArtworkIdentity;

    Model updateConnection(boolean connected) {
        this.connected = connected;
        return model();
    }

    Model updateState(RemoteState state) {
        String nextArtworkIdentity = state == null || !state.hasTrack
                ? null : state.artworkKey();
        if (!Objects.equals(expectedArtworkIdentity, nextArtworkIdentity)) {
            displayedArtworkIdentity = null;
        }
        expectedArtworkIdentity = nextArtworkIdentity;
        this.state = state;
        return model();
    }

    Model updateArtwork(boolean available) {
        if (available && expectedArtworkIdentity != null) {
            displayedArtworkIdentity = expectedArtworkIdentity;
        }
        return model();
    }

    Model reset() {
        state = null;
        connected = false;
        expectedArtworkIdentity = null;
        displayedArtworkIdentity = null;
        return model();
    }

    Model model() {
        boolean visible = state != null && state.hasTrack;
        String identity = visible ? state.trackIdentity() : null;
        return new Model(
                visible,
                visible && connected,
                visible && "playing".equals(state.playbackState),
                visible && expectedArtworkIdentity != null
                        && expectedArtworkIdentity.equals(displayedArtworkIdentity),
                identity,
                visible ? state.title : null,
                visible ? state.artist : null
        );
    }
}
