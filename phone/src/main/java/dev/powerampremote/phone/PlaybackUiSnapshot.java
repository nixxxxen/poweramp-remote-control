package dev.powerampremote.phone;

/** Service-owned playback anchor sent to a newly rebound Activity without network polling. */
final class PlaybackUiSnapshot {
    final int positionSeconds;
    final long capturedRealtimeMilliseconds;

    private final int anchorPositionSeconds;
    private final long anchorRealtimeMilliseconds;
    private final int durationSeconds;
    private final boolean playing;

    private PlaybackUiSnapshot(
            int anchorPositionSeconds,
            long anchorRealtimeMilliseconds,
            int durationSeconds,
            boolean playing,
            int positionSeconds,
            long capturedRealtimeMilliseconds
    ) {
        this.anchorPositionSeconds = Math.max(anchorPositionSeconds, 0);
        this.anchorRealtimeMilliseconds = anchorRealtimeMilliseconds;
        this.durationSeconds = Math.max(durationSeconds, 0);
        this.playing = playing;
        this.positionSeconds = Math.max(positionSeconds, 0);
        this.capturedRealtimeMilliseconds = capturedRealtimeMilliseconds;
    }

    static PlaybackUiSnapshot anchor(RemoteState state, long nowMilliseconds) {
        int position = state == null || state.positionSeconds == null
                ? 0 : Math.max(state.positionSeconds, 0);
        int duration = state == null || state.durationSeconds == null
                ? 0 : Math.max(state.durationSeconds, 0);
        if (duration > 0) position = Math.min(position, duration);
        return new PlaybackUiSnapshot(
                position,
                nowMilliseconds,
                duration,
                state != null && "playing".equals(state.playbackState),
                position,
                nowMilliseconds
        );
    }

    PlaybackUiSnapshot capturedAt(long nowMilliseconds) {
        long position = anchorPositionSeconds;
        if (playing) {
            position += Math.max(0L, nowMilliseconds - anchorRealtimeMilliseconds) / 1_000L;
        }
        if (durationSeconds > 0) position = Math.min(position, durationSeconds);
        int captured = position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) position;
        return new PlaybackUiSnapshot(
                anchorPositionSeconds,
                anchorRealtimeMilliseconds,
                durationSeconds,
                playing,
                captured,
                nowMilliseconds
        );
    }

    PlaybackUiSnapshot frozenAt(long nowMilliseconds) {
        PlaybackUiSnapshot captured = capturedAt(nowMilliseconds);
        return new PlaybackUiSnapshot(
                captured.positionSeconds,
                nowMilliseconds,
                durationSeconds,
                false,
                captured.positionSeconds,
                nowMilliseconds
        );
    }
}
