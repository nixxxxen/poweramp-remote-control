package dev.powerampremote.phone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Strict bounded parser for additive API v1 Library/Search pages. */
final class LibraryPageParser {
    private static final Pattern PAGE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{24}");
    private static final Pattern ARTWORK = Pattern.compile(
            "/api/v1/library/artwork/tracks/[1-9][0-9]{0,18}"
    );

    static LibraryPage parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String category = boundedText(root.getString("category"));
            int limit = root.getInt("limit");
            int offset = root.getInt("offset");
            if (category.isEmpty() || limit < 1 || limit > 100 || offset < 0) {
                throw new JSONException("Invalid page header");
            }
            JSONArray values = root.getJSONArray("items");
            if (values.length() > limit || values.length() > 100) {
                throw new JSONException("Too many items");
            }
            List<LibraryItem> items = new ArrayList<>(values.length());
            for (int index = 0; index < values.length(); index++) {
                items.add(parseItem(values.getJSONObject(index)));
            }
            String nextPageToken = nullableString(root, "nextPageToken");
            if (nextPageToken != null && !PAGE_TOKEN.matcher(nextPageToken).matches()) {
                throw new JSONException("Invalid page token");
            }
            boolean truncated = root.getBoolean("truncated");
            if (truncated && nextPageToken != null) {
                throw new JSONException("Truncated page cannot continue");
            }
            return new LibraryPage(category, limit, offset, items, nextPageToken, truncated);
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid library page", exception);
        }
    }

    static LibraryItem parseItem(JSONObject object) throws JSONException {
        String type = boundedText(object.getString("type"));
        long id = positiveLong(object, "id");
        Long entryId = nullablePositiveLong(object, "entryId");
        Long parentId = nullableNonNegativeLong(object, "parentId");
        String title = nullableBoundedText(object, "title");
        String artist = nullableBoundedText(object, "artist");
        String album = nullableBoundedText(object, "album");
        Long duration = nullableNonNegativeLong(object, "durationMilliseconds");
        Long dateAdded = optionalNullableNonNegativeLong(
                object, "dateAddedEpochSeconds"
        );
        Long playCount = optionalNullableNonNegativeLong(object, "playCount");
        Integer trackCount = nullableNonNegativeInt(object, "trackCount");
        String artwork = nullableString(object, "artwork");
        if (artwork != null && !ARTWORK.matcher(artwork).matches()) {
            throw new JSONException("Unexpected artwork path");
        }
        LibraryPlayTarget playTarget = object.isNull("play")
                ? null : LibraryPlayTarget.parse(object.getJSONObject("play"));
        if ("track".equals(type) && playTarget != null
                && (!"track".equals(playTarget.type)
                || playTarget.id == null || playTarget.id != id)) {
            throw new JSONException("Track play target does not match row");
        } else if ("playlist_entry".equals(type)) {
            if (entryId == null) throw new JSONException("Playlist entry ID is required");
            if (playTarget != null && (!"playlist_entry".equals(playTarget.type)
                    || !entryId.equals(playTarget.entryId))) {
                throw new JSONException("Playlist play target does not match entry");
            }
        } else if ("queue_entry".equals(type)) {
            if (entryId == null) throw new JSONException("Queue entry ID is required");
            if (playTarget != null && (!"queue_entry".equals(playTarget.type)
                    || !entryId.equals(playTarget.entryId))) {
                throw new JSONException("Queue play target does not match entry");
            }
        }
        LibraryBrowseTarget browseTarget = !object.has("browse") || object.isNull("browse")
                ? null : LibraryBrowseTarget.parse(object.getJSONObject("browse"));
        if (browseTarget != null
                && (!"artist".equals(type) || browseTarget.id != id)) {
            throw new JSONException("Browse target does not match item");
        }
        Boolean current = object.isNull("current") ? null : object.getBoolean("current");
        return new LibraryItem(
                type, id, entryId, parentId, title, artist, album,
                duration, dateAdded, playCount, trackCount, artwork,
                playTarget, browseTarget, current
        );
    }

    private static String boundedText(String value) throws JSONException {
        if (value == null || value.length() > 1000) throw new JSONException("Invalid text");
        return value;
    }

    private static String nullableBoundedText(JSONObject object, String key) throws JSONException {
        String value = nullableString(object, key);
        return value == null ? null : boundedText(value);
    }

    private static String nullableString(JSONObject object, String key) throws JSONException {
        if (!object.has(key)) throw new JSONException("Missing field");
        if (object.isNull(key)) return null;
        Object value = object.get(key);
        if (!(value instanceof String)) throw new JSONException("Expected string");
        return (String) value;
    }

    private static long positiveLong(JSONObject object, String key) throws JSONException {
        Long value = integerLong(object, key, false);
        if (value == null || value <= 0L) throw new JSONException("Invalid positive integer");
        return value;
    }

    private static Long nullablePositiveLong(JSONObject object, String key) throws JSONException {
        Long value = integerLong(object, key, true);
        if (value != null && value <= 0L) throw new JSONException("Invalid positive integer");
        return value;
    }

    private static Long nullableNonNegativeLong(JSONObject object, String key) throws JSONException {
        Long value = integerLong(object, key, true);
        if (value != null && value < 0L) throw new JSONException("Invalid non-negative integer");
        return value;
    }

    private static Long optionalNullableNonNegativeLong(
            JSONObject object,
            String key
    ) throws JSONException {
        if (!object.has(key)) return null;
        return nullableNonNegativeLong(object, key);
    }

    private static Integer nullableNonNegativeInt(JSONObject object, String key) throws JSONException {
        Long value = integerLong(object, key, true);
        if (value == null) return null;
        if (value < 0L || value > Integer.MAX_VALUE) throw new JSONException("Invalid count");
        return value.intValue();
    }

    private static Long integerLong(JSONObject object, String key, boolean nullable)
            throws JSONException {
        if (!object.has(key)) throw new JSONException("Missing field");
        if (object.isNull(key)) {
            if (nullable) return null;
            throw new JSONException("Missing number");
        }
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("Expected integer");
        }
        return ((Number) value).longValue();
    }

    private LibraryPageParser() { }
}
