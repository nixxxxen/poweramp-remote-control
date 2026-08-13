package dev.powerampremote.phone;

import java.util.Map;

/** Validates the stable API v1 state schema without guessing absent optional fields. */
final class RemoteStateParser {
    private static final String ARTWORK_PATH = "/api/v1/artwork";

    private RemoteStateParser() {
    }

    static RemoteState parse(String json) {
        Map<String, Object> values = FlatJsonParser.parseObject(json);
        long apiVersion = requiredLong(values, "apiVersion");
        if (apiVersion != 1L) throw new IllegalArgumentException("unsupported API version");
        long revision = requiredLong(values, "revision");
        if (revision < 0L) throw new IllegalArgumentException("negative revision");
        String artwork = nullableString(values, "artwork");
        if (artwork != null && !ARTWORK_PATH.equals(artwork)) {
            throw new IllegalArgumentException("unexpected artwork path");
        }
        String playbackState = nullableString(values, "playbackState");
        if (playbackState != null && !"playing".equals(playbackState)
                && !"paused".equals(playbackState) && !"stopped".equals(playbackState)) {
            throw new IllegalArgumentException("invalid playback state");
        }
        Integer rating = nullableNonNegativeInt(values, "rating");
        if (rating != null && rating > 5) throw new IllegalArgumentException("invalid rating");

        return new RemoteState(
                revision,
                requiredBoolean(values, "powerampAvailable"),
                requiredBoolean(values, "hasTrack"),
                nullableString(values, "title"),
                nullableString(values, "artist"),
                nullableString(values, "album"),
                artwork,
                nullableNonNegativeInt(values, "fileType"),
                nullableString(values, "fileTypeName"),
                nullableString(values, "codec"),
                nullableNonNegativeInt(values, "bitsPerSample"),
                nullableNonNegativeInt(values, "sampleRate"),
                nullableNonNegativeInt(values, "bitRate"),
                nullableNonNegativeInt(values, "sourceCategory"),
                nullableString(values, "sourceCategoryName"),
                nullableString(values, "sourceCategoryUri"),
                nullableNonNegativeInt(values, "positionInList"),
                nullableNonNegativeInt(values, "listSize"),
                nullableNonNegativeInt(values, "durationSeconds"),
                nullableNonNegativeInt(values, "positionSeconds"),
                playbackState,
                rating,
                nullableBoolean(values, "liked"),
                nullableBoolean(values, "disliked"),
                nullableBoolean(values, "shuffle"),
                nullableNonNegativeInt(values, "shuffleMode"),
                nullableNonNegativeInt(values, "volume"),
                nullableNonNegativeInt(values, "volumeMax"),
                nullableBoolean(values, "volumeControlAvailable")
        );
    }

    private static long requiredLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Long)) throw new IllegalArgumentException("missing integer: " + key);
        return (Long) value;
    }

    private static boolean requiredBoolean(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("missing boolean: " + key);
        return (Boolean) value;
    }

    private static String nullableString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("expected string: " + key);
        return (String) value;
    }

    private static Boolean nullableBoolean(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("expected boolean: " + key);
        return (Boolean) value;
    }

    private static Integer nullableNonNegativeInt(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof Long)) throw new IllegalArgumentException("expected integer: " + key);
        long number = (Long) value;
        if (number < 0L || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer out of range: " + key);
        }
        return (int) number;
    }
}
