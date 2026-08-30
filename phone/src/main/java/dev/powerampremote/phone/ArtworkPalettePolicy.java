package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministically extracts and normalizes characteristic colors from sampled pixels. */
final class ArtworkPalettePolicy {
    private static final int HUE_BUCKETS = 24;
    private static final int SATURATION_BUCKETS = 3;
    private static final int LIGHTNESS_BUCKETS = 4;
    private static final int BUCKET_COUNT =
            HUE_BUCKETS * SATURATION_BUCKETS * LIGHTNESS_BUCKETS;
    private static final float MIN_SOURCE_SATURATION = 0.10f;
    private static final float MIN_LIGHTNESS = 0.055f;
    private static final float MAX_LIGHTNESS = 0.94f;
    private static final float SECOND_COLOR_DISTANCE = 0.18f;
    private static final float THIRD_COLOR_DISTANCE = 0.12f;

    private ArtworkPalettePolicy() {
    }

    static ArtworkPalette fromPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) return ArtworkPalette.FALLBACK;

        Bucket[] buckets = new Bucket[BUCKET_COUNT];
        int usablePixels = 0;
        for (int color : pixels) {
            if (ArtworkPalette.alpha(color) < 128) continue;
            float red = ArtworkPalette.red(color) / 255f;
            float green = ArtworkPalette.green(color) / 255f;
            float blue = ArtworkPalette.blue(color) / 255f;
            Hsl hsl = Hsl.fromRgb(red, green, blue);
            if (hsl.saturation < MIN_SOURCE_SATURATION
                    || hsl.lightness < MIN_LIGHTNESS
                    || hsl.lightness > MAX_LIGHTNESS) {
                continue;
            }

            int hueBucket = Math.min((int) (hsl.hue * HUE_BUCKETS), HUE_BUCKETS - 1);
            int saturationBucket = Math.min(
                    (int) (hsl.saturation * SATURATION_BUCKETS),
                    SATURATION_BUCKETS - 1
            );
            int lightnessBucket = Math.min(
                    (int) (hsl.lightness * LIGHTNESS_BUCKETS),
                    LIGHTNESS_BUCKETS - 1
            );
            int index = (hueBucket * SATURATION_BUCKETS + saturationBucket)
                    * LIGHTNESS_BUCKETS + lightnessBucket;
            Bucket bucket = buckets[index];
            if (bucket == null) {
                bucket = new Bucket(index);
                buckets[index] = bucket;
            }
            bucket.add(color);
            usablePixels++;
        }

        if (usablePixels < 2) return ArtworkPalette.FALLBACK;
        List<Candidate> candidates = new ArrayList<>();
        for (Bucket bucket : buckets) {
            if (bucket != null) candidates.add(bucket.candidate());
        }
        candidates.sort(Comparator
                .comparingDouble((Candidate candidate) -> candidate.score)
                .reversed()
                .thenComparingInt(candidate -> candidate.bucketIndex));
        if (candidates.isEmpty()) return ArtworkPalette.FALLBACK;

        List<Integer> selected = new ArrayList<>(3);
        selected.add(candidates.get(0).normalizedColor);
        for (int index = 1; index < candidates.size() && selected.size() < 3; index++) {
            int candidate = candidates.get(index).normalizedColor;
            float minimumDistance = selected.size() == 1
                    ? SECOND_COLOR_DISTANCE : THIRD_COLOR_DISTANCE;
            if (distanceFromEvery(candidate, selected) >= minimumDistance) {
                selected.add(candidate);
            }
        }
        if (selected.size() < 2) return ArtworkPalette.FALLBACK;
        int tertiary = selected.size() == 3 ? selected.get(2) : selected.get(1);
        return new ArtworkPalette(
                selected.size(),
                selected.get(0),
                selected.get(1),
                tertiary
        );
    }

    static int normalizeForDarkInterface(int color) {
        Hsl source = Hsl.fromRgb(
                ArtworkPalette.red(color) / 255f,
                ArtworkPalette.green(color) / 255f,
                ArtworkPalette.blue(color) / 255f
        );
        float saturation = clamp(0.28f + source.saturation * 0.38f, 0.32f, 0.68f);
        float lightness = clamp(0.22f + source.lightness * 0.24f, 0.22f, 0.44f);
        return Hsl.toColor(source.hue, saturation, lightness);
    }

    private static float distanceFromEvery(int candidate, List<Integer> selected) {
        float minimum = Float.MAX_VALUE;
        for (int color : selected) {
            minimum = Math.min(minimum, colorDistance(candidate, color));
        }
        return minimum;
    }

    private static float colorDistance(int first, int second) {
        float red = (ArtworkPalette.red(first) - ArtworkPalette.red(second)) / 255f;
        float green = (ArtworkPalette.green(first) - ArtworkPalette.green(second)) / 255f;
        float blue = (ArtworkPalette.blue(first) - ArtworkPalette.blue(second)) / 255f;
        return (float) Math.sqrt((red * red + green * green + blue * blue) / 3f);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static final class Bucket {
        final int index;
        long redTotal;
        long greenTotal;
        long blueTotal;
        int count;

        Bucket(int index) {
            this.index = index;
        }

        void add(int color) {
            redTotal += ArtworkPalette.red(color);
            greenTotal += ArtworkPalette.green(color);
            blueTotal += ArtworkPalette.blue(color);
            count++;
        }

        Candidate candidate() {
            int red = (int) Math.round((double) redTotal / count);
            int green = (int) Math.round((double) greenTotal / count);
            int blue = (int) Math.round((double) blueTotal / count);
            int averaged = 0xff000000 | (red << 16) | (green << 8) | blue;
            Hsl hsl = Hsl.fromRgb(red / 255f, green / 255f, blue / 255f);
            double prevalence = count * (0.7d + hsl.saturation);
            double usableLightness = 1d - Math.abs(hsl.lightness - 0.46d) * 0.45d;
            return new Candidate(
                    index,
                    normalizeForDarkInterface(averaged),
                    prevalence * Math.max(0.5d, usableLightness)
            );
        }
    }

    private static final class Candidate {
        final int bucketIndex;
        final int normalizedColor;
        final double score;

        Candidate(int bucketIndex, int normalizedColor, double score) {
            this.bucketIndex = bucketIndex;
            this.normalizedColor = normalizedColor;
            this.score = score;
        }
    }

    private static final class Hsl {
        final float hue;
        final float saturation;
        final float lightness;

        Hsl(float hue, float saturation, float lightness) {
            this.hue = hue;
            this.saturation = saturation;
            this.lightness = lightness;
        }

        static Hsl fromRgb(float red, float green, float blue) {
            float maximum = Math.max(red, Math.max(green, blue));
            float minimum = Math.min(red, Math.min(green, blue));
            float delta = maximum - minimum;
            float lightness = (maximum + minimum) / 2f;
            if (delta == 0f) return new Hsl(0f, 0f, lightness);
            float saturation = delta / (1f - Math.abs(2f * lightness - 1f));
            float hue;
            if (maximum == red) {
                hue = ((green - blue) / delta) % 6f;
            } else if (maximum == green) {
                hue = (blue - red) / delta + 2f;
            } else {
                hue = (red - green) / delta + 4f;
            }
            hue /= 6f;
            if (hue < 0f) hue += 1f;
            return new Hsl(hue, saturation, lightness);
        }

        static int toColor(float hue, float saturation, float lightness) {
            float chroma = (1f - Math.abs(2f * lightness - 1f)) * saturation;
            float section = hue * 6f;
            float secondary = chroma * (1f - Math.abs(section % 2f - 1f));
            float red;
            float green;
            float blue;
            if (section < 1f) {
                red = chroma;
                green = secondary;
                blue = 0f;
            } else if (section < 2f) {
                red = secondary;
                green = chroma;
                blue = 0f;
            } else if (section < 3f) {
                red = 0f;
                green = chroma;
                blue = secondary;
            } else if (section < 4f) {
                red = 0f;
                green = secondary;
                blue = chroma;
            } else if (section < 5f) {
                red = secondary;
                green = 0f;
                blue = chroma;
            } else {
                red = chroma;
                green = 0f;
                blue = secondary;
            }
            float match = lightness - chroma / 2f;
            int redByte = Math.round((red + match) * 255f);
            int greenByte = Math.round((green + match) * 255f);
            int blueByte = Math.round((blue + match) * 255f);
            return 0xff000000 | (redByte << 16) | (greenByte << 8) | blueByte;
        }
    }
}
