package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class ArtworkPalettePolicyTest {
    @Test
    public void sameColorMultisetProducesDeterministicPalette() {
        int[] pixels = colors(
                0xffff3028, 80,
                0xff1677ff, 55,
                0xff19c77b, 35,
                0xffffffff, 25
        );
        int[] reversed = pixels.clone();
        for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
            int swap = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = swap;
        }

        ArtworkPalette first = ArtworkPalettePolicy.fromPixels(pixels);
        ArtworkPalette second = ArtworkPalettePolicy.fromPixels(reversed);

        assertEquals(first, second);
        assertNotEquals(ArtworkPalette.FALLBACK, first);
    }

    @Test
    public void absentAndMonochromeArtworkUseFallback() {
        assertEquals(ArtworkPalette.FALLBACK, ArtworkPalettePolicy.fromPixels(null));
        assertEquals(ArtworkPalette.FALLBACK, ArtworkPalettePolicy.fromPixels(new int[0]));

        int[] monochrome = new int[200];
        Arrays.fill(monochrome, 0xff245f9c);
        assertEquals(ArtworkPalette.FALLBACK, ArtworkPalettePolicy.fromPixels(monochrome));

        int[] almostWhite = new int[200];
        Arrays.fill(almostWhite, 0xfffbfbfb);
        assertEquals(ArtworkPalette.FALLBACK, ArtworkPalettePolicy.fromPixels(almostWhite));
    }

    @Test
    public void brightArtworkColorsAreNormalizedForDarkInterface() {
        ArtworkPalette palette = ArtworkPalettePolicy.fromPixels(colors(
                0xffffff00, 80,
                0xffff00ff, 70,
                0xff00ffff, 60,
                0xffffffff, 40
        ));

        assertNotEquals(ArtworkPalette.FALLBACK, palette);
        for (int index = 0; index < palette.colorCount(); index++) {
            int color = palette.colorAt(index);
            int maximumChannel = Math.max(
                    ArtworkPalette.red(color),
                    Math.max(ArtworkPalette.green(color), ArtworkPalette.blue(color))
            );
            int minimumChannel = Math.min(
                    ArtworkPalette.red(color),
                    Math.min(ArtworkPalette.green(color), ArtworkPalette.blue(color))
            );
            assertTrue("normalized color is too bright", maximumChannel < 200);
            assertTrue("normalized color lost all character", maximumChannel - minimumChannel > 35);
        }
    }

    @Test
    public void cacheKeySeparatesServersAndUsesStableArtworkIdentity() {
        String artworkKey = "track\u0000artist\u0000album";

        assertEquals(
                ArtworkPaletteCacheKey.create("server-one", artworkKey),
                ArtworkPaletteCacheKey.create("server-one", artworkKey)
        );
        assertNotEquals(
                ArtworkPaletteCacheKey.create("server-one", artworkKey),
                ArtworkPaletteCacheKey.create("server-two", artworkKey)
        );
    }

    private static int[] colors(Object... values) {
        int size = 0;
        for (int index = 1; index < values.length; index += 2) size += (int) values[index];
        int[] result = new int[size];
        int output = 0;
        for (int index = 0; index < values.length; index += 2) {
            int color = (int) values[index];
            int count = (int) values[index + 1];
            Arrays.fill(result, output, output + count, color);
            output += count;
        }
        return result;
    }
}
