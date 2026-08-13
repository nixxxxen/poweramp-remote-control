package dev.powerampremote.phone;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/** Produces Binder-safe artwork bytes for MediaSession metadata. */
final class RemoteSessionArtwork {
    private static final int MAX_DIMENSION_PIXELS = 512;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int[] JPEG_QUALITIES = {85, 65, 45};

    private RemoteSessionArtwork() {
    }

    static byte[] encode(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        Bitmap sessionBitmap = source;
        int largestDimension = Math.max(source.getWidth(), source.getHeight());
        if (largestDimension > MAX_DIMENSION_PIXELS) {
            float scale = (float) MAX_DIMENSION_PIXELS / largestDimension;
            sessionBitmap = Bitmap.createScaledBitmap(
                    source,
                    Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)),
                    true
            );
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(128 * 1024);
            for (int quality : JPEG_QUALITIES) {
                output.reset();
                if (!sessionBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return null;
                }
                if (output.size() > 0 && output.size() <= MAX_PAYLOAD_BYTES) {
                    return output.toByteArray();
                }
            }
            return null;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            if (sessionBitmap != source) sessionBitmap.recycle();
        }
    }
}
