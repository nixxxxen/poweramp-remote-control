package dev.powerampremote.phone;

/** Service-owned playback anchor sent to a newly rebound Activity without network polling. */
final class PlaybackUiSnapshot {
    final int positionSeconds;
    final long capturedRealtimeMilliseconds;

    private final long anchorPositionMilliseconds;
    private final long anchorRealtimeMilliseconds;
    private final long durationMilliseconds;
    private final boolean playing;

    private PlaybackUiSnapshot(
            long anchorPositionMilliseconds,
            long anchorRealtimeMilliseconds,
            long durationMilliseconds,
            boolean playing,
            int positionSeconds,
            long capturedRealtimeMilliseconds
    ) {
        this.anchorPositionMilliseconds = Math.max(anchorPositionMilliseconds, 0L);
        this.anchorRealtimeMilliseconds = anchorRealtimeMilliseconds;
        this.durationMilliseconds = Math.max(durationMilliseconds, 0L);
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
                position * 1_000L,
                nowMilliseconds,
                duration * 1_000L,
                state != null && "playing".equals(state.playbackState),
                position,
                nowMilliseconds
        );
    }

    PlaybackUiSnapshot capturedAt(long nowMilliseconds) {
        long positionMilliseconds = positionMillisecondsAt(nowMilliseconds);
        long position = positionMilliseconds / 1_000L;
        int captured = position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) position;
        return new PlaybackUiSnapshot(
                anchorPositionMilliseconds,
                anchorRealtimeMilliseconds,
                durationMilliseconds,
                playing,
                captured,
                nowMilliseconds
        );
    }

    PlaybackUiSnapshot frozenAt(long nowMilliseconds) {
        long frozenPositionMilliseconds = positionMillisecondsAt(nowMilliseconds);
        long position = frozenPositionMilliseconds / 1_000L;
        int captured = position > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) position;
        return new PlaybackUiSnapshot(
                frozenPositionMilliseconds,
                nowMilliseconds,
                durationMilliseconds,
                false,
                captured,
                nowMilliseconds
        );
    }

    long positionMillisecondsAt(long nowMilliseconds) {
        long position = anchorPositionMilliseconds;
        if (playing) {
            position += Math.max(0L, nowMilliseconds - anchorRealtimeMilliseconds);
        }
        if (durationMilliseconds > 0L) position = Math.min(position, durationMilliseconds);
        return Math.max(position, 0L);
    }

    long confirmedRealtimeMilliseconds() {
        return anchorRealtimeMilliseconds;
    }

    boolean isAdvancing() {
        return playing;
    }
}
