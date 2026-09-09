package dev.powerampremote.phone;

import org.json.JSONException;
import org.json.JSONObject;

/** Validated additive browse target carried by grouped Search rows. */
final class LibraryBrowseTarget {
    static final String TYPE_ARTIST_MEMBERSHIP = "artist_membership";

    final String type;
    final long id;

    LibraryBrowseTarget(String type, long id) {
        if (!TYPE_ARTIST_MEMBERSHIP.equals(type) || id <= 0L) {
            throw new IllegalArgumentException("Invalid library browse target");
        }
        this.type = type;
        this.id = id;
    }

    static LibraryBrowseTarget parse(JSONObject object) throws JSONException {
        if (object.length() != 2 || !object.has("type") || !object.has("id")) {
            throw new JSONException("Unexpected browse target fields");
        }
        Object idValue = object.get("id");
        if (!(idValue instanceof Integer) && !(idValue instanceof Long)) {
            throw new JSONException("Invalid browse target ID");
        }
        try {
            return new LibraryBrowseTarget(
                    object.getString("type"), ((Number) idValue).longValue()
            );
        } catch (IllegalArgumentException exception) {
            throw new JSONException("Invalid browse target");
        }
    }
}
