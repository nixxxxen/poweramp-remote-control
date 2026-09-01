package dev.powerampremote.phone;

/**
 * Presentation-only progress reconciliation around the service-owned monotonic snapshot.
 * The authoritative playback anchor remains {@link PlaybackUiSnapshot}.
 */
final class PlaybackProgressCoordinator {
    static final long CORRECTION_EPSILON_MILLISECONDS = 24L;
    static final long SMOOTH_CORRECTION_LIMIT_MILLISECONDS = 1_500L;
    static final long CORRECTION_DURATION_MILLISECONDS = 280L;
    static final long SEEK_CONFIRMATION_TOLERANCE_MILLISECONDS = 2_000L;
    static final long PENDING_SEEK_TIMEOUT_MILLISECONDS = 2_000L;
    static final long REDUCED_MOTION_TICK_MILLISECONDS = 250L;

    enum SnapshotApplication {
        IMMEDIATE,
        SMOOTHED,
        PENDING_IGNORED,
        DRAG_DEFERRED
    }

    static final class DisplayedSecondTracker {
        private int displayedSecond = Integer.MIN_VALUE;

        boolean shouldUpdate(long positionMilliseconds) {
            int second = secondsFloor(positionMilliseconds);
            if (displayedSecond == second) return false;
            displayedSecond = second;
            return true;
        }

        void reset() {
            displayedSecond = Integer.MIN_VALUE;
        }
    }

    private PlaybackUiSnapshot confirmedSnapshot;
    private long durationMilliseconds;
    private boolean dragging;
    private long dragPositionMilliseconds;
    private boolean pendingSeek;
    private long pendingSeekPositionMilliseconds;
    private long pendingSeekRealtimeMilliseconds;
    private long pendingSeekExpiresRealtimeMilliseconds;
    private boolean pendingSeekAdvancing;
    private boolean frozen;
    private long frozenPositionMilliseconds;
    private boolean correctionActive;
    private long correctionOffsetMilliseconds;
    private long correctionStartRealtimeMilliseconds;

    void updateDuration(long durationMilliseconds) {
        this.durationMilliseconds = Math.max(durationMilliseconds, 0L);
        frozenPositionMilliseconds = clamp(frozenPositionMilliseconds);
        dragPositionMilliseconds = clamp(dragPositionMilliseconds);
        pendingSeekPositionMilliseconds = clamp(pendingSeekPositionMilliseconds);
    }

    void onTrackChanged(long durationMilliseconds) {
        updateDuration(durationMilliseconds);
        confirmedSnapshot = null;
        dragging = false;
        pendingSeek = false;
        correctionActive = false;
        frozen = true;
        frozenPositionMilliseconds = 0L;
    }

    SnapshotApplication onSnapshot(
            PlaybackUiSnapshot snapshot,
            long nowMilliseconds,
            long durationMilliseconds,
            boolean forceImmediate,
            boolean animationsEnabled
    ) {
        if (snapshot == null) return SnapshotApplication.IMMEDIATE;
        updateDuration(durationMilliseconds);

        PlaybackUiSnapshot previousSnapshot = confirmedSnapshot;
        long previousVisualPosition = visualPositionMillisecondsAt(nowMilliseconds, false);
        boolean pendingExpired = pendingSeek
                && nowMilliseconds >= pendingSeekExpiresRealtimeMilliseconds;
        boolean seekConfirmed = pendingSeek && Math.abs(
                snapshot.positionSeconds * 1_000L - pendingSeekPositionMilliseconds
        ) <= SEEK_CONFIRMATION_TOLERANCE_MILLISECONDS;

        confirmedSnapshot = snapshot;
        frozen = false;
        if (dragging) return SnapshotApplication.DRAG_DEFERRED;
        if (pendingSeek && !seekConfirmed && !pendingExpired) {
            return SnapshotApplication.PENDING_IGNORED;
        }
        if (pendingSeek) {
            pendingSeek = false;
            if (pendingExpired) forceImmediate = true;
        }

        long authoritativePosition = snapshot.positionMillisecondsAt(nowMilliseconds);
        boolean advancementChanged = previousSnapshot != null
                && previousSnapshot.isAdvancing() != snapshot.isAdvancing();
        if (forceImmediate || previousSnapshot == null || advancementChanged
                || !snapshot.isAdvancing() || !animationsEnabled) {
            correctionActive = false;
            return SnapshotApplication.IMMEDIATE;
        }

        long difference = previousVisualPosition - authoritativePosition;
        long absoluteDifference = Math.abs(difference);
        if (absoluteDifference <= CORRECTION_EPSILON_MILLISECONDS
                || absoluteDifference > SMOOTH_CORRECTION_LIMIT_MILLISECONDS) {
            correctionActive = false;
            return SnapshotApplication.IMMEDIATE;
        }

        correctionActive = true;
        correctionOffsetMilliseconds = difference;
        correctionStartRealtimeMilliseconds = nowMilliseconds;
        return SnapshotApplication.SMOOTHED;
    }

    void startDragging(long positionMilliseconds) {
        dragging = true;
        dragPositionMilliseconds = clamp(positionMilliseconds);
        pendingSeek = false;
        correctionActive = false;
    }

    void updateDragPosition(long positionMilliseconds) {
        if (dragging) dragPositionMilliseconds = clamp(positionMilliseconds);
    }

    int commitSeek(
            long positionMilliseconds,
            long nowMilliseconds,
            boolean playbackAdvancing
    ) {
        dragging = false;
        pendingSeek = true;
        pendingSeekPositionMilliseconds = clamp(positionMilliseconds);
        pendingSeekRealtimeMilliseconds = nowMilliseconds;
        pendingSeekExpiresRealtimeMilliseconds = nowMilliseconds
                + PENDING_SEEK_TIMEOUT_MILLISECONDS;
        pendingSeekAdvancing = playbackAdvancing;
        correctionActive = false;
        frozen = false;
        return secondsRounded(pendingSeekPositionMilliseconds);
    }

    void onCommandFailure(long nowMilliseconds) {
        dragging = false;
        pendingSeek = false;
        correctionActive = false;
        frozen = false;
        frozenPositionMilliseconds = confirmedPositionMillisecondsAt(nowMilliseconds);
    }

    void freezeAt(long nowMilliseconds) {
        long position = pendingSeek
                ? confirmedPositionMillisecondsAt(nowMilliseconds)
                : visualPositionMillisecondsAt(nowMilliseconds, false);
        dragging = false;
        pendingSeek = false;
        correctionActive = false;
        frozen = true;
        frozenPositionMilliseconds = clamp(position);
    }

    long positionMillisecondsAt(long nowMilliseconds) {
        return visualPositionMillisecondsAt(nowMilliseconds, true);
    }

    boolean isDragging() {
        return dragging;
    }

    boolean hasPendingSeek() {
        return pendingSeek;
    }

    boolean correctionActive() {
        return correctionActive;
    }

    boolean shouldRunContinuousUpdates(
            boolean hostVisible,
            boolean connected,
            boolean reportedPlaying
    ) {
        if (!hostVisible || !connected || dragging || frozen || !reportedPlaying) return false;
        if (pendingSeek) return pendingSeekAdvancing;
        return confirmedSnapshot != null && confirmedSnapshot.isAdvancing();
    }

    long pendingTimeoutDelayMilliseconds(long nowMilliseconds) {
        if (!pendingSeek || pendingSeekAdvancing) return -1L;
        return Math.max(0L, pendingSeekExpiresRealtimeMilliseconds - nowMilliseconds);
    }

    private long visualPositionMillisecondsAt(long nowMilliseconds, boolean expirePending) {
        if (dragging) return clamp(dragPositionMilliseconds);
        if (pendingSeek) {
            if (expirePending && nowMilliseconds >= pendingSeekExpiresRealtimeMilliseconds) {
                pendingSeek = false;
                correctionActive = false;
                return clamp(confirmedPositionMillisecondsAt(nowMilliseconds));
            }
            long position = pendingSeekPositionMilliseconds;
            if (pendingSeekAdvancing) {
                position += Math.max(0L, nowMilliseconds - pendingSeekRealtimeMilliseconds);
            }
            return clamp(position);
        }
        if (frozen) return clamp(frozenPositionMilliseconds);

        long position = confirmedPositionMillisecondsAt(nowMilliseconds);
        if (!correctionActive) return clamp(position);
        long elapsed = Math.max(0L, nowMilliseconds - correctionStartRealtimeMilliseconds);
        if (elapsed >= CORRECTION_DURATION_MILLISECONDS) {
            correctionActive = false;
            return clamp(position);
        }
        float progress = elapsed / (float) CORRECTION_DURATION_MILLISECONDS;
        float eased = progress * progress * (3f - 2f * progress);
        long remainingOffset = Math.round(correctionOffsetMilliseconds * (1f - eased));
        return clamp(position + remainingOffset);
    }

    private long confirmedPositionMillisecondsAt(long nowMilliseconds) {
        return confirmedSnapshot == null
                ? frozenPositionMilliseconds
                : confirmedSnapshot.positionMillisecondsAt(nowMilliseconds);
    }

    private long clamp(long positionMilliseconds) {
        long position = Math.max(positionMilliseconds, 0L);
        return durationMilliseconds > 0L
                ? Math.min(position, durationMilliseconds) : position;
    }

    static int secondsFloor(long positionMilliseconds) {
        long seconds = Math.max(positionMilliseconds, 0L) / 1_000L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    static int secondsRounded(long positionMilliseconds) {
        long seconds = (Math.max(positionMilliseconds, 0L) + 500L) / 1_000L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
