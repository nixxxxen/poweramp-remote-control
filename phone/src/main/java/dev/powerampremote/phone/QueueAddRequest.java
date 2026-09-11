package dev.powerampremote.phone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded append request made only from identities supplied by Server rows. */
final class QueueAddRequest {
    static final int MAX_ITEMS = 100;
    final List<LibraryPlayTarget> items;

    QueueAddRequest(List<LibraryPlayTarget> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Queue add batch must contain 1..100 items");
        }
        ArrayList<LibraryPlayTarget> copy = new ArrayList<>(items.size());
        for (LibraryPlayTarget target : items) {
            if (target == null || !("track".equals(target.type)
                    || "playlist_entry".equals(target.type)
                    || "queue_entry".equals(target.type))) {
                throw new IllegalArgumentException("Unsupported Queue add target");
            }
            copy.add(target);
        }
        this.items = Collections.unmodifiableList(copy);
    }

    String toJson() {
        try {
            JSONArray values = new JSONArray();
            for (LibraryPlayTarget target : items) {
                values.put(new JSONObject(target.toJson()));
            }
            return new JSONObject().put("items", values).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode Queue add request", impossible);
        }
    }
}
