package dev.powerampremote.server;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict structured identities for one non-transactional public Queue append operation. */
final class QueueAddRequest {
    static final int MAX_ITEMS = 100;

    final List<LibraryItem.PlayTarget> items;

    private QueueAddRequest(List<LibraryItem.PlayTarget> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    static QueueAddRequest parse(String json) {
        try {
            JSONTokener tokener = new JSONTokener(json == null ? "" : json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != '\0') {
                throw invalid();
            }
            JSONObject root = (JSONObject) value;
            requireKeys(root, "items");
            Object rawItems = root.get("items");
            if (!(rawItems instanceof JSONArray)) throw invalid();
            JSONArray array = (JSONArray) rawItems;
            if (array.length() < 1 || array.length() > MAX_ITEMS) throw invalid();
            List<LibraryItem.PlayTarget> targets = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                Object rawItem = array.get(index);
                if (!(rawItem instanceof JSONObject)) throw invalid();
                targets.add(parseItem((JSONObject) rawItem));
            }
            return new QueueAddRequest(targets);
        } catch (JSONException | StackOverflowError exception) {
            throw invalid();
        }
    }

    private static LibraryItem.PlayTarget parseItem(JSONObject object) throws JSONException {
        Object rawType = object.opt("type");
        if (!(rawType instanceof String)) throw invalid();
        switch ((String) rawType) {
            case "track":
                requireKeys(object, "type", "id");
                return LibraryItem.PlayTarget.track(positiveLong(object, "id"));
            case "playlist_entry":
                requireKeys(object, "type", "playlistId", "entryId");
                return LibraryItem.PlayTarget.playlistEntry(
                        positiveLong(object, "playlistId"),
                        positiveLong(object, "entryId")
                );
            case "queue_entry":
                requireKeys(object, "type", "entryId");
                return LibraryItem.PlayTarget.queueEntry(
                        positiveLong(object, "entryId")
                );
            default:
                throw invalid();
        }
    }

    private static long positiveLong(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long result = ((Number) value).longValue();
        if (result <= 0L) throw invalid();
        return result;
    }

    private static void requireKeys(JSONObject object, String... keys) {
        if (object.length() != keys.length) throw invalid();
        for (String key : keys) {
            if (!object.has(key) || object.isNull(key)) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid queue add request");
    }
}
