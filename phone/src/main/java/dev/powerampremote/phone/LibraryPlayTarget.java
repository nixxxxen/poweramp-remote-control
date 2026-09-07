package dev.powerampremote.phone;

import org.json.JSONException;
import org.json.JSONObject;

/** Structured API v1 play target supplied by Server; never a client-provided URI. */
final class LibraryPlayTarget {
    final String type;
    final Long id;
    final Long playlistId;
    final Long entryId;

    private LibraryPlayTarget(String type, Long id, Long playlistId, Long entryId) {
        this.type = type;
        this.id = id;
        this.playlistId = playlistId;
        this.entryId = entryId;
    }

    static LibraryPlayTarget parse(JSONObject object) throws JSONException {
        if (object == null) return null;
        String type = object.getString("type");
        switch (type) {
            case "track":
            case "album":
            case "playlist":
                requireFields(object, "type", "id");
                return new LibraryPlayTarget(type, positiveLong(object, "id"), null, null);
            case "playlist_entry":
                requireFields(object, "type", "playlistId", "entryId");
                return new LibraryPlayTarget(
                        type,
                        null,
                        positiveLong(object, "playlistId"),
                        positiveLong(object, "entryId")
                );
            case "queue_entry":
                requireFields(object, "type", "entryId");
                return new LibraryPlayTarget(
                        type, null, null, positiveLong(object, "entryId")
                );
            default:
                throw new JSONException("Unsupported play target");
        }
    }

    String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("type", type);
            if (id != null) object.put("id", id);
            if (playlistId != null) object.put("playlistId", playlistId);
            if (entryId != null) object.put("entryId", entryId);
            return object.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode play target", impossible);
        }
    }

    private static long positiveLong(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("ID is not an integer");
        }
        long result = ((Number) value).longValue();
        if (result <= 0L) throw new JSONException("ID is not positive");
        return result;
    }

    private static void requireFields(JSONObject object, String... fields) throws JSONException {
        if (object.length() != fields.length) throw new JSONException("Unexpected target field");
        for (String field : fields) {
            if (!object.has(field) || object.isNull(field)) {
                throw new JSONException("Missing target field");
            }
        }
    }
}
