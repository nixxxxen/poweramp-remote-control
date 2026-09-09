package dev.powerampremote.server;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stable additive API v1 JSON contract for library, search, and queue responses. */
final class LibraryJson {
    static String capabilities(LibraryAccessState state) {
        try {
            JSONObject root = new JSONObject();
            root.put("status", state.status.wireName);
            root.put("permissionRequired",
                    state.status == LibraryAccessState.Status.PERMISSION_REQUIRED);
            root.put("permissionRequestAvailable", state.permissionRequestAvailable());

            JSONObject routes = new JSONObject();
            routes.put("tracks", "/api/v1/library/tracks");
            routes.put("artists", "/api/v1/library/artists");
            routes.put("albums", "/api/v1/library/albums");
            routes.put("folders", "/api/v1/library/folders");
            routes.put("folderTree", "/api/v1/library/folder-tree/0/folders");
            routes.put("playlists", "/api/v1/library/playlists");
            routes.put("search", "/api/v1/search");
            routes.put("categorizedSearch", "/api/v1/search/grouped");
            routes.put("queue", "/api/v1/queue");
            routes.put("play", "/api/v1/library/play");
            root.put("routes", routes);

            JSONObject pagination = new JSONObject();
            pagination.put("defaultLimit", PowerampLibraryContract.DEFAULT_PAGE_SIZE);
            pagination.put("maximumLimit", PowerampLibraryContract.MAX_PAGE_SIZE);
            pagination.put(
                    "maximumContinuationRows",
                    PowerampLibraryContract.MAX_CONTINUATION_ROWS
            );
            pagination.put("providerOffsetSupported", false);
            root.put("pagination", pagination);

            JSONObject queue = new JSONObject();
            queue.put("read", true);
            queue.put("playExisting", true);
            queue.put("add", false);
            queue.put("remove", false);
            queue.put("reorder", false);
            queue.put("playNext", false);
            root.put("queueCapabilities", queue);
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to serialize library capabilities", impossible);
        }
    }

    static String page(PowerampLibrarySource.Page page) {
        try {
            JSONObject root = new JSONObject();
            root.put("category", page.category);
            root.put("limit", page.limit);
            root.put("offset", page.offset);
            JSONArray items = new JSONArray();
            for (LibraryItem item : page.items) {
                items.put(item(item));
            }
            root.put("items", items);
            root.put("nextPageToken", nullable(page.nextPageToken));
            root.put("truncated", page.truncated);
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to serialize library page", impossible);
        }
    }

    static String categorizedSearch(CategorizedSearch search) {
        try {
            JSONObject root = new JSONObject();
            root.put("query", search.query);
            root.put("limit", search.limit);
            root.put("trackMatch", search.trackMatch.wireName);
            JSONArray sections = new JSONArray();
            for (CategorizedSearch.Section section : search.sections) {
                JSONObject serializedSection = new JSONObject();
                serializedSection.put("type", section.type.wireName);
                JSONArray items = new JSONArray();
                for (LibraryItem item : section.items) {
                    items.put(item(item));
                }
                serializedSection.put("items", items);
                serializedSection.put("truncated", section.truncated);
                sections.put(serializedSection);
            }
            root.put("sections", sections);
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(
                    "Unable to serialize categorized Search", impossible
            );
        }
    }

    static String accepted(LibraryItem.PlayTarget target) {
        try {
            JSONObject root = new JSONObject();
            root.put("accepted", true);
            root.put("target", playTarget(target));
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to serialize play response", impossible);
        }
    }

    static LibraryItem.PlayTarget parsePlayTarget(String json) {
        try {
            JSONObject object = new JSONObject(json);
            Object typeValue = object.opt("type");
            if (!(typeValue instanceof String)) {
                throw new IllegalArgumentException("Play target type is required");
            }
            LibraryItem.PlayTarget.Type type = LibraryItem.PlayTarget.Type.fromWireName(
                    (String) typeValue
            );
            switch (type) {
                case TRACK:
                    requireKeys(object, "type", "id");
                    return LibraryItem.PlayTarget.track(positiveLong(object, "id"));
                case ALBUM:
                    requireKeys(object, "type", "id");
                    return LibraryItem.PlayTarget.album(positiveLong(object, "id"));
                case PLAYLIST:
                    requireKeys(object, "type", "id");
                    return LibraryItem.PlayTarget.playlist(positiveLong(object, "id"));
                case PLAYLIST_ENTRY:
                    requireKeys(object, "type", "playlistId", "entryId");
                    return LibraryItem.PlayTarget.playlistEntry(
                            positiveLong(object, "playlistId"),
                            positiveLong(object, "entryId")
                    );
                case QUEUE_ENTRY:
                    requireKeys(object, "type", "entryId");
                    return LibraryItem.PlayTarget.queueEntry(positiveLong(object, "entryId"));
                default:
                    throw new IllegalArgumentException("Unsupported play target");
            }
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid play target", exception);
        }
    }

    private static JSONObject item(LibraryItem item) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", item.type.wireName);
        object.put("id", item.id);
        object.put("entryId", nullable(item.entryId));
        object.put("parentId", nullable(item.parentId));
        object.put("title", nullable(item.title));
        object.put("artist", nullable(item.artist));
        object.put("album", nullable(item.album));
        object.put("durationMilliseconds", nullable(item.durationMilliseconds));
        object.put("trackCount", nullable(item.trackCount));
        object.put("artwork", nullable(item.artworkPath));
        object.put("play", item.playTarget == null
                ? JSONObject.NULL
                : playTarget(item.playTarget));
        object.put("current", nullable(item.current));
        return object;
    }

    private static JSONObject playTarget(LibraryItem.PlayTarget target) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", target.type.wireName);
        switch (target.type) {
            case PLAYLIST_ENTRY:
                object.put("playlistId", target.containerId);
                object.put("entryId", target.id);
                break;
            case QUEUE_ENTRY:
                object.put("entryId", target.id);
                break;
            case TRACK:
            case ALBUM:
            case PLAYLIST:
            default:
                object.put("id", target.id);
                break;
        }
        return object;
    }

    private static long positiveLong(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("ID must be an integer");
        }
        long number = ((Number) value).longValue();
        if (number <= 0L) {
            throw new IllegalArgumentException("ID must be positive");
        }
        return number;
    }

    private static void requireKeys(JSONObject object, String... keys) {
        if (object.length() != keys.length) {
            throw new IllegalArgumentException("Unexpected play target field");
        }
        for (String key : keys) {
            if (!object.has(key) || object.isNull(key)) {
                throw new IllegalArgumentException("Missing play target field");
            }
        }
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private LibraryJson() {
    }
}
