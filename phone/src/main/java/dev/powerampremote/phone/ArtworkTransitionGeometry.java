package dev.powerampremote.phone;

/** Pure geometry for the shared swipe/button artwork transition. */
final class ArtworkTransitionGeometry {
    private static final float PENDING_OFFSET_FRACTION = 0.16f;
    private static final float CACHED_PENDING_OFFSET_FRACTION = 0.28f;
    private static final float RUBBER_BAND_LIMIT_FRACTION = 0.20f;
    private static final float RUBBER_BAND_RESPONSE_FRACTION = 0.22f;

    static final class Frame {
        final float outgoingTranslationX;
        final float outgoingAlpha;
        final float incomingTranslationX;
        final float incomingAlpha;

        private Frame(
                float outgoingTranslationX,
                float outgoingAlpha,
                float incomingTranslationX,
                float incomingAlpha
        ) {
            this.outgoingTranslationX = outgoingTranslationX;
            this.outgoingAlpha = outgoingAlpha;
            this.incomingTranslationX = incomingTranslationX;
            this.incomingAlpha = incomingAlpha;
        }
    }

    private ArtworkTransitionGeometry() {
    }

    static float pendingOffset(
            ArtworkNavigationCoordinator.Direction direction,
            float width
    ) {
        float magnitude = Math.max(0f, width) * PENDING_OFFSET_FRACTION;
        if (direction == ArtworkNavigationCoordinator.Direction.NEXT) return -magnitude;
        if (direction == ArtworkNavigationCoordinator.Direction.PREVIOUS) return magnitude;
        return 0f;
    }

    static float cachedPendingOffset(
            ArtworkNavigationCoordinator.Direction direction,
            float width
    ) {
        float magnitude = Math.max(0f, width) * CACHED_PENDING_OFFSET_FRACTION;
        if (direction == ArtworkNavigationCoordinator.Direction.NEXT) return -magnitude;
        if (direction == ArtworkNavigationCoordinator.Direction.PREVIOUS) return magnitude;
        return 0f;
    }

    static float rubberBandTranslation(float rawTranslation, float width) {
        float safeWidth = Math.max(1f, width);
        float limit = safeWidth * RUBBER_BAND_LIMIT_FRACTION;
        float response = safeWidth * RUBBER_BAND_RESPONSE_FRACTION;
        float magnitude = limit * (float) Math.tanh(Math.abs(rawTranslation) / response);
        return Math.copySign(magnitude, rawTranslation);
    }

    static Frame carouselFrame(
            ArtworkNavigationCoordinator.Direction direction,
            float width,
            float outgoingTranslation
    ) {
        float safeWidth = Math.max(0f, width);
        float clampedOutgoing = Math.max(
                -safeWidth,
                Math.min(safeWidth, outgoingTranslation)
        );
        float incomingTranslation = clampedOutgoing;
        if (direction == ArtworkNavigationCoordinator.Direction.NEXT) {
            incomingTranslation += safeWidth;
        } else if (direction == ArtworkNavigationCoordinator.Direction.PREVIOUS) {
            incomingTranslation -= safeWidth;
        }
        return new Frame(clampedOutgoing, 1f, incomingTranslation, 1f);
    }

    static Frame frame(
            ArtworkNavigationCoordinator.Direction direction,
            float width,
            float outgoingStartTranslationX,
            float progress
    ) {
        float incomingStart = direction == ArtworkNavigationCoordinator.Direction.NEXT
                ? Math.max(0f, width)
                : direction == ArtworkNavigationCoordinator.Direction.PREVIOUS
                ? -Math.max(0f, width) : 0f;
        return frame(
                direction,
                width,
                outgoingStartTranslationX,
                incomingStart,
                progress
        );
    }

    static Frame frame(
            ArtworkNavigationCoordinator.Direction direction,
            float width,
            float outgoingStartTranslationX,
            float incomingStartTranslationX,
            float progress
    ) {
        float safeWidth = Math.max(0f, width);
        float fraction = Math.max(0f, Math.min(1f, progress));
        if (direction == ArtworkNavigationCoordinator.Direction.NEUTRAL) {
            return new Frame(
                    outgoingStartTranslationX * (1f - fraction),
                    1f - fraction,
                    0f,
                    fraction
            );
        }
        float outgoingEnd = direction == ArtworkNavigationCoordinator.Direction.NEXT
                ? -safeWidth : safeWidth;
        return new Frame(
                lerp(outgoingStartTranslationX, outgoingEnd, fraction),
                lerp(1f, 0.82f, fraction),
                lerp(incomingStartTranslationX, 0f, fraction),
                1f
        );
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }
}
