package dev.powerampremote.phone;

/** Pure retargeting policy used when palette changes interrupt one another. */
final class ArtworkPaletteTransition {
    static final class Plan {
        final ArtworkPalette start;
        final ArtworkPalette end;
        final boolean animate;

        Plan(ArtworkPalette start, ArtworkPalette end, boolean animate) {
            this.start = start;
            this.end = end;
            this.animate = animate;
        }
    }

    private ArtworkPaletteTransition() {
    }

    static Plan retarget(
            ArtworkPalette previousStart,
            ArtworkPalette previousEnd,
            float previousProgress,
            ArtworkPalette next,
            boolean animationsEnabled
    ) {
        ArtworkPalette current = ArtworkPalette.interpolate(
                previousStart,
                previousEnd,
                previousProgress
        );
        ArtworkPalette target = next == null ? ArtworkPalette.FALLBACK : next;
        if (!animationsEnabled || current.equals(target)) {
            return new Plan(target, target, false);
        }
        return new Plan(current, target, true);
    }
}
