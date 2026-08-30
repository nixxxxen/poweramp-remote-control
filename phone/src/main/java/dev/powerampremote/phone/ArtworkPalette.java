package dev.powerampremote.phone;

/** Immutable dark-interface palette derived from one artwork. */
final class ArtworkPalette {
    static final ArtworkPalette FALLBACK = new ArtworkPalette(
            3,
            0xff24475a,
            0xff3a2854,
            0xff203c38
    );

    private final int colorCount;
    private final int primary;
    private final int secondary;
    private final int tertiary;

    ArtworkPalette(int colorCount, int primary, int secondary, int tertiary) {
        if (colorCount < 2 || colorCount > 3) {
            throw new IllegalArgumentException("A palette must contain two or three colors");
        }
        this.colorCount = colorCount;
        this.primary = opaque(primary);
        this.secondary = opaque(secondary);
        this.tertiary = opaque(tertiary);
    }

    int colorCount() {
        return colorCount;
    }

    int colorAt(int index) {
        switch (index) {
            case 0:
                return primary;
            case 1:
                return secondary;
            case 2:
                return colorCount == 3 ? tertiary : secondary;
            default:
                throw new IndexOutOfBoundsException("color index=" + index);
        }
    }

    int[] toStoredColors() {
        return new int[]{colorCount, primary, secondary, tertiary};
    }

    static ArtworkPalette fromStoredColors(int[] stored) {
        if (stored == null || stored.length != 4 || stored[0] < 2 || stored[0] > 3) {
            return null;
        }
        return new ArtworkPalette(stored[0], stored[1], stored[2], stored[3]);
    }

    static ArtworkPalette interpolate(ArtworkPalette start, ArtworkPalette end, float progress) {
        if (start == null) start = FALLBACK;
        if (end == null) end = FALLBACK;
        float amount = clamp(progress, 0f, 1f);
        if (amount <= 0f) return start;
        if (amount >= 1f) return end;
        int count = Math.max(start.colorCount, end.colorCount);
        return new ArtworkPalette(
                count,
                interpolateColor(start.colorAt(0), end.colorAt(0), amount),
                interpolateColor(start.colorAt(1), end.colorAt(1), amount),
                interpolateColor(start.colorAt(2), end.colorAt(2), amount)
        );
    }

    static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00ffffff);
    }

    private static int interpolateColor(int start, int end, float amount) {
        int red = Math.round(red(start) + (red(end) - red(start)) * amount);
        int green = Math.round(green(start) + (green(end) - green(start)) * amount);
        int blue = Math.round(blue(start) + (blue(end) - blue(start)) * amount);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    static int red(int color) {
        return (color >> 16) & 0xff;
    }

    static int green(int color) {
        return (color >> 8) & 0xff;
    }

    static int blue(int color) {
        return color & 0xff;
    }

    static int alpha(int color) {
        return (color >>> 24) & 0xff;
    }

    private static int opaque(int color) {
        return 0xff000000 | (color & 0x00ffffff);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ArtworkPalette)) return false;
        ArtworkPalette other = (ArtworkPalette) value;
        return colorCount == other.colorCount
                && primary == other.primary
                && secondary == other.secondary
                && tertiary == other.tertiary;
    }

    @Override
    public int hashCode() {
        int result = colorCount;
        result = 31 * result + primary;
        result = 31 * result + secondary;
        result = 31 * result + tertiary;
        return result;
    }
}
