package dev.powerampremote.server;

/** Stable JSON representation shared by GET /state and every WebSocket event message. */
final class RemoteStateJson {
    static final String ARTWORK_PATH = "/api/v1/artwork";

    private RemoteStateJson() {
    }

    static String toJson(RemotePlaybackState state, long nowMilliseconds) {
        StringBuilder json = new StringBuilder(768);
        TrackInfo track = state.track;
        TrackInfo.AudioProperties audio = track != null ? track.audio : null;
        TrackInfo.PlaybackSource source = track != null ? track.source : null;
        int rating = track != null ? track.rating : -1;

        json.append('{');
        appendNumber(json, "apiVersion", 1L);
        appendNumber(json, "revision", state.revision);
        appendBoolean(json, "powerampAvailable", state.powerampAvailable);
        appendBoolean(json, "hasTrack", track != null);
        appendString(json, "title", track != null ? track.title : null);
        appendString(json, "artist", track != null ? track.artist : null);
        appendString(json, "album", track != null ? track.album : null);
        appendString(
                json,
                "artwork",
                state.artworkAvailable && state.artworkId > 0L ? ARTWORK_PATH : null
        );
        appendNullableNumber(json, "fileType", audio != null ? audio.fileType : -1);
        appendString(
                json,
                "fileTypeName",
                audio != null ? TrackMetadataFormatter.fileTypeName(audio.fileType) : null
        );
        appendString(json, "codec", audio != null ? audio.codec : null);
        appendNullableNumber(
                json,
                "bitsPerSample",
                audio != null ? audio.bitsPerSample : -1
        );
        appendNullableNumber(json, "sampleRate", audio != null ? audio.sampleRate : -1);
        // Keep Poweramp's raw value: its public API does not define a guaranteed unit.
        appendNullableNumber(json, "bitRate", audio != null ? audio.bitRate : -1);
        appendNullableNumber(
                json,
                "sourceCategory",
                source != null ? source.category : -1
        );
        appendString(
                json,
                "sourceCategoryName",
                source != null ? TrackMetadataFormatter.categoryName(source.category) : null
        );
        appendString(json, "sourceCategoryUri", source != null ? source.categoryUri : null);
        // Keep Poweramp's raw list position: the public API does not define its index base.
        appendNullableNumber(
                json,
                "positionInList",
                source != null ? source.positionInList : -1
        );
        appendNullableNumber(json, "listSize", source != null ? source.listSize : -1);
        appendNullableNumber(
                json,
                "durationSeconds",
                track != null && track.durationSeconds > 0 ? track.durationSeconds : -1
        );
        appendNullableNumber(
                json,
                "positionSeconds",
                track != null && state.positionAvailable
                        ? state.positionAt(nowMilliseconds)
                        : -1
        );
        appendString(json, "playbackState", playbackStateName(state.playbackState));
        appendNullableNumber(json, "rating", rating);
        appendNullableBoolean(json, "liked", rating >= 0 ? rating == 5 : null);
        appendNullableBoolean(json, "disliked", rating >= 0 ? rating == 1 : null);
        appendNullableBoolean(
                json,
                "shuffle",
                state.shuffleMode >= 0
                        ? state.shuffleMode > PowerampContract.ShuffleModes.NONE
                        : null
        );
        appendNullableNumber(json, "shuffleMode", state.shuffleMode);
        appendNullableNumber(json, "volume", state.volume);
        appendNullableNumber(json, "volumeMax", state.volumeMax);
        appendBoolean(json, "volumeControlAvailable", state.volumeControlAvailable);
        json.append('}');
        return json.toString();
    }

    private static String playbackStateName(int state) {
        switch (state) {
            case PowerampContract.STATE_STOPPED:
                return "stopped";
            case PowerampContract.STATE_PLAYING:
                return "playing";
            case PowerampContract.STATE_PAUSED:
                return "paused";
            default:
                return null;
        }
    }

    private static void appendNumber(StringBuilder json, String key, long value) {
        appendKey(json, key);
        json.append(value);
    }

    private static void appendNullableNumber(StringBuilder json, String key, long value) {
        appendKey(json, key);
        if (value < 0L) {
            json.append("null");
        } else {
            json.append(value);
        }
    }

    private static void appendBoolean(StringBuilder json, String key, boolean value) {
        appendKey(json, key);
        json.append(value);
    }

    private static void appendNullableBoolean(StringBuilder json, String key, Boolean value) {
        appendKey(json, key);
        json.append(value == null ? "null" : value.toString());
    }

    private static void appendString(StringBuilder json, String key, String value) {
        appendKey(json, key);
        if (value == null) {
            json.append("null");
            return;
        }
        appendQuoted(json, value);
    }

    private static void appendKey(StringBuilder json, String key) {
        if (json.length() > 1) {
            json.append(',');
        }
        appendQuoted(json, key);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        json.append("\\u00");
                        json.append(HEX[(character >> 4) & 0x0F]);
                        json.append(HEX[character & 0x0F]);
                    } else {
                        json.append(character);
                    }
                    break;
            }
        }
        json.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
