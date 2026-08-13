package dev.powerampremote.phone;

/** Pure mapping from API v1 state to the subset exposed through the phone MediaSession. */
final class RemoteSessionSnapshot {
    final boolean hasTrack;
    final boolean controlsAvailable;
    final boolean playing;
    final boolean seekAvailable;
    final String mediaId;
    final String title;
    final String artist;
    final String album;
    final long durationMilliseconds;
    final long positionMilliseconds;
    final int volume;
    final int volumeMax;
    final boolean volumeAvailable;
    final boolean volumeControlAvailable;

    private RemoteSessionSnapshot(
            boolean hasTrack,
            boolean controlsAvailable,
            boolean playing,
            boolean seekAvailable,
            String mediaId,
            String title,
            String artist,
            String album,
            long durationMilliseconds,
            long positionMilliseconds,
            int volume,
            int volumeMax,
            boolean volumeAvailable,
            boolean volumeControlAvailable
    ) {
        this.hasTrack = hasTrack;
        this.controlsAvailable = controlsAvailable;
        this.playing = playing;
        this.seekAvailable = seekAvailable;
        this.mediaId = mediaId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMilliseconds = durationMilliseconds;
        this.positionMilliseconds = positionMilliseconds;
        this.volume = volume;
        this.volumeMax = volumeMax;
        this.volumeAvailable = volumeAvailable;
        this.volumeControlAvailable = volumeControlAvailable;
    }

    static RemoteSessionSnapshot from(RemoteState state, boolean connected) {
        if (state == null) {
            return new RemoteSessionSnapshot(
                    false, false, false, false, "", null, null, null, 0L, 0L,
                    0, 0, false, false
            );
        }
        boolean hasTrack = state.hasTrack;
        boolean controlsAvailable = connected && state.powerampAvailable;
        long durationMilliseconds = secondsToMilliseconds(state.durationSeconds);
        long positionMilliseconds = secondsToMilliseconds(state.positionSeconds);
        if (durationMilliseconds > 0L) {
            positionMilliseconds = Math.min(positionMilliseconds, durationMilliseconds);
        }
        boolean volumeAvailable = state.volume != null && state.volume >= 0
                && state.volumeMax != null && state.volumeMax > 0;
        int volumeMax = volumeAvailable ? state.volumeMax : 0;
        int volume = volumeAvailable ? Math.min(state.volume, volumeMax) : 0;
        return new RemoteSessionSnapshot(
                hasTrack,
                controlsAvailable,
                controlsAvailable && "playing".equals(state.playbackState),
                controlsAvailable && hasTrack && durationMilliseconds > 0L,
                hasTrack ? "poweramp-remote:" + state.trackIdentity() : "",
                clean(state.title),
                clean(state.artist),
                clean(state.album),
                durationMilliseconds,
                positionMilliseconds,
                volume,
                volumeMax,
                volumeAvailable,
                connected && volumeAvailable
                        && Boolean.TRUE.equals(state.volumeControlAvailable)
        );
    }

    private static long secondsToMilliseconds(Integer seconds) {
        return seconds == null || seconds < 0 ? 0L : seconds.longValue() * 1_000L;
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
