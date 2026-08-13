package dev.powerampremote.server;

/** Immutable state shared by the bound UI and the foreground service's network API. */
final class RemotePlaybackState {
    final long revision;
    final boolean powerampAvailable;
    final TrackInfo track;
    final int playbackState;
    final int anchorPositionSeconds;
    final long anchorRealtimeMilliseconds;
    final boolean positionAvailable;
    final int shuffleMode;
    final long artworkId;
    final boolean artworkAvailable;

    RemotePlaybackState(
            long revision,
            boolean powerampAvailable,
            TrackInfo track,
            int playbackState,
            int anchorPositionSeconds,
            long anchorRealtimeMilliseconds,
            int shuffleMode,
            long artworkId,
            boolean artworkAvailable
    ) {
        this(
                revision,
                powerampAvailable,
                track,
                playbackState,
                anchorPositionSeconds,
                anchorRealtimeMilliseconds,
                shuffleMode,
                artworkId,
                artworkAvailable,
                track != null
        );
    }

    RemotePlaybackState(
            long revision,
            boolean powerampAvailable,
            TrackInfo track,
            int playbackState,
            int anchorPositionSeconds,
            long anchorRealtimeMilliseconds,
            int shuffleMode,
            long artworkId,
            boolean artworkAvailable,
            boolean positionAvailable
    ) {
        this.revision = revision;
        this.powerampAvailable = powerampAvailable;
        this.track = track;
        this.playbackState = playbackState;
        this.anchorPositionSeconds = Math.max(anchorPositionSeconds, 0);
        this.anchorRealtimeMilliseconds = anchorRealtimeMilliseconds;
        this.positionAvailable = positionAvailable;
        this.shuffleMode = shuffleMode;
        this.artworkId = artworkId;
        this.artworkAvailable = artworkAvailable;
    }

    static RemotePlaybackState initial(long nowMilliseconds) {
        return new RemotePlaybackState(
                0L,
                false,
                null,
                PowerampContract.STATE_UNKNOWN,
                0,
                nowMilliseconds,
                -1,
                0L,
                false
        );
    }

    boolean hasTrack() {
        return track != null;
    }

    int positionAt(long nowMilliseconds) {
        long position = anchorPositionSeconds;
        if (playbackState == PowerampContract.STATE_PLAYING) {
            position += Math.max(0L, nowMilliseconds - anchorRealtimeMilliseconds) / 1_000L;
        }
        if (track != null && track.durationSeconds > 0) {
            position = Math.min(position, track.durationSeconds);
        }
        return position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) position;
    }
}
