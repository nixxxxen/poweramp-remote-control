package dev.powerampremote.phone;

import android.graphics.Bitmap;

/** Samples a decoded artwork without creating a resized or full-size bitmap copy. */
final class ArtworkPaletteBitmapAnalyzer {
    private static final int MAX_SAMPLE_COLUMNS = 40;
    private static final int MAX_SAMPLE_ROWS = 40;

    private ArtworkPaletteBitmapAnalyzer() {
    }

    static ArtworkPalette analyze(Bitmap artwork) {
        if (artwork == null || artwork.isRecycled()
                || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) {
            return ArtworkPalette.FALLBACK;
        }
        try {
            int width = artwork.getWidth();
            int height = artwork.getHeight();
            int columns = Math.min(width, MAX_SAMPLE_COLUMNS);
            int rows = Math.min(height, MAX_SAMPLE_ROWS);
            int[] pixels = new int[columns * rows];
            int output = 0;
            for (int row = 0; row < rows; row++) {
                int y = sampleCoordinate(row, rows, height);
                for (int column = 0; column < columns; column++) {
                    int x = sampleCoordinate(column, columns, width);
                    pixels[output++] = artwork.getPixel(x, y);
                }
            }
            return ArtworkPalettePolicy.fromPixels(pixels);
        } catch (RuntimeException exception) {
            return ArtworkPalette.FALLBACK;
        }
    }

    private static int sampleCoordinate(int index, int sampleCount, int sourceSize) {
        if (sampleCount <= 1 || sourceSize <= 1) return 0;
        return Math.round(index * (sourceSize - 1f) / (sampleCount - 1f));
    }
}
