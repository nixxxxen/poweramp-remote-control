package dev.powerampremote.phone;

/** Pure single-pointer horizontal swipe recognizer for the square artwork surface. */
final class ArtworkSwipeGesture {
    private static final float DIRECTION_DOMINANCE = 1.25f;
    private static final float COMMIT_WIDTH_FRACTION = 0.24f;
    private static final float MAX_COMMIT_WIDTH_FRACTION = 0.45f;
    private static final float TOUCH_SLOP_MULTIPLIER = 2.5f;

    static final class Move {
        final boolean trackingHorizontal;
        final boolean cancelled;
        final float translationX;
        final boolean performHaptic;
        final boolean thresholdReached;

        private Move(
                boolean trackingHorizontal,
                boolean cancelled,
                float translationX,
                boolean performHaptic,
                boolean thresholdReached
        ) {
            this.trackingHorizontal = trackingHorizontal;
            this.cancelled = cancelled;
            this.translationX = translationX;
            this.performHaptic = performHaptic;
            this.thresholdReached = thresholdReached;
        }
    }

    static final class Finish {
        final boolean committed;
        final ArtworkNavigationCoordinator.Direction direction;
        final boolean performHaptic;

        private Finish(
                boolean committed,
                ArtworkNavigationCoordinator.Direction direction,
                boolean performHaptic
        ) {
            this.committed = committed;
            this.direction = direction;
            this.performHaptic = performHaptic;
        }
    }

    private final float startX;
    private final float startY;
    private final float artworkWidth;
    private final float touchSlop;
    private final float commitThreshold;
    private boolean horizontal;
    private boolean cancelled;
    private boolean finished;
    private boolean hapticPerformed;

    ArtworkSwipeGesture(
            float startX,
            float startY,
            float artworkWidth,
            float touchSlop
    ) {
        this.startX = startX;
        this.startY = startY;
        this.artworkWidth = Math.max(1f, artworkWidth);
        this.touchSlop = Math.max(0f, touchSlop);
        commitThreshold = commitThresholdPixels(this.artworkWidth, this.touchSlop);
    }

    Move move(float x, float y, int pointerCount, boolean controlsAvailable) {
        if (finished || cancelled) return cancelledMove();
        if (!controlsAvailable || pointerCount != 1) {
            cancelled = true;
            return cancelledMove();
        }

        float deltaX = x - startX;
        float deltaY = y - startY;
        float absoluteX = Math.abs(deltaX);
        float absoluteY = Math.abs(deltaY);
        if (!horizontal) {
            if (Math.max(absoluteX, absoluteY) <= touchSlop) return idleMove();
            if (absoluteX <= touchSlop || absoluteX < absoluteY * DIRECTION_DOMINANCE) {
                cancelled = true;
                return cancelledMove();
            }
            horizontal = true;
        } else if (absoluteY > absoluteX && absoluteY > touchSlop * DIRECTION_DOMINANCE) {
            cancelled = true;
            return cancelledMove();
        }

        float translationX = Math.max(
                -artworkWidth,
                Math.min(artworkWidth, deltaX)
        );
        boolean thresholdReached = absoluteX >= commitThreshold;
        boolean performHaptic = thresholdReached && !hapticPerformed;
        if (performHaptic) hapticPerformed = true;
        return new Move(true, false, translationX, performHaptic, thresholdReached);
    }

    Finish finish(float x, float y, int pointerCount, boolean controlsAvailable) {
        if (finished) return noCommit();
        Move finalMove = move(x, y, pointerCount, controlsAvailable);
        finished = true;
        if (finalMove.cancelled || !finalMove.trackingHorizontal
                || !finalMove.thresholdReached) {
            return new Finish(false, null, finalMove.performHaptic);
        }
        return new Finish(
                true,
                finalMove.translationX < 0f
                        ? ArtworkNavigationCoordinator.Direction.NEXT
                        : ArtworkNavigationCoordinator.Direction.PREVIOUS,
                finalMove.performHaptic
        );
    }

    void cancel() {
        cancelled = true;
        finished = true;
    }

    static float commitThresholdPixels(float artworkWidth, float touchSlop) {
        float width = Math.max(1f, artworkWidth);
        float preferred = Math.max(
                width * COMMIT_WIDTH_FRACTION,
                Math.max(0f, touchSlop) * TOUCH_SLOP_MULTIPLIER
        );
        return Math.min(preferred, width * MAX_COMMIT_WIDTH_FRACTION);
    }

    private static Move idleMove() {
        return new Move(false, false, 0f, false, false);
    }

    private static Move cancelledMove() {
        return new Move(false, true, 0f, false, false);
    }

    private static Finish noCommit() {
        return new Finish(false, null, false);
    }
}
