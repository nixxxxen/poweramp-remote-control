package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Ordered, bounded selection keyed by exact Server-provided occurrence identity. */
final class TrackSelection {
    private final LinkedHashMap<String, LibraryPlayTarget> selected = new LinkedHashMap<>();

    boolean toggle(LibraryPlayTarget target) {
        String key = key(target);
        if (selected.remove(key) != null) return false;
        if (selected.size() >= QueueAddRequest.MAX_ITEMS) return false;
        selected.put(key, target);
        return true;
    }

    boolean contains(LibraryPlayTarget target) {
        return target != null && selected.containsKey(key(target));
    }

    int size() {
        return selected.size();
    }

    boolean isEmpty() {
        return selected.isEmpty();
    }

    List<LibraryPlayTarget> targets() {
        return new ArrayList<>(selected.values());
    }

    void removeFirst(int count) {
        List<String> keys = new ArrayList<>(selected.keySet());
        for (int index = 0; index < count && index < keys.size(); index++) {
            selected.remove(keys.get(index));
        }
    }

    void clear() {
        selected.clear();
    }

    private static String key(LibraryPlayTarget target) {
        if (target == null) throw new IllegalArgumentException("Missing track identity");
        if ("track".equals(target.type) && target.id != null) {
            return "track:" + target.id;
        }
        if ("playlist_entry".equals(target.type)
                && target.playlistId != null && target.entryId != null) {
            return "playlist_entry:" + target.playlistId + ':' + target.entryId;
        }
        if ("queue_entry".equals(target.type) && target.entryId != null) {
            return "queue_entry:" + target.entryId;
        }
        throw new IllegalArgumentException("Unsupported track identity");
    }
}
