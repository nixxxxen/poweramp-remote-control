package dev.powerampremote.phone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Server-advertised criteria; absence keeps older API v1 Servers on Poweramp order. */
final class LibrarySortCapabilities {
    private final Set<LibrarySort.Criterion> criteria;
    private final boolean queueAddSupported;

    private LibrarySortCapabilities(
            Set<LibrarySort.Criterion> criteria,
            boolean queueAddSupported
    ) {
        this.criteria = Collections.unmodifiableSet(EnumSet.copyOf(criteria));
        this.queueAddSupported = queueAddSupported;
    }

    static LibrarySortCapabilities defaultOnly() {
        return new LibrarySortCapabilities(
                EnumSet.of(LibrarySort.Criterion.DEFAULT), false
        );
    }

    static LibrarySortCapabilities parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            EnumSet<LibrarySort.Criterion> supported = EnumSet.of(
                    LibrarySort.Criterion.DEFAULT
            );
            if (root.has("trackSorting") && !root.isNull("trackSorting")) {
                JSONObject sorting = root.getJSONObject("trackSorting");
                JSONArray values = sorting.getJSONArray("criteria");
                JSONArray directions = sorting.getJSONArray("directions");
                boolean ascending = false;
                boolean descending = false;
                for (int index = 0; index < directions.length(); index++) {
                    Object value = directions.get(index);
                    if ("asc".equals(value)) ascending = true;
                    if ("desc".equals(value)) descending = true;
                }
                if (!ascending || !descending) {
                    throw new JSONException("Incomplete sort directions");
                }
                supported.clear();
                for (int index = 0; index < values.length(); index++) {
                    Object value = values.get(index);
                    if (!(value instanceof String)) {
                        throw new JSONException("Invalid sort criterion");
                    }
                    try {
                        supported.add(LibrarySort.Criterion.fromWireName((String) value));
                    } catch (IllegalArgumentException ignored) {
                        // A newer Server criterion is safely ignored by this Phone build.
                    }
                }
                if (!supported.contains(LibrarySort.Criterion.DEFAULT)) {
                    throw new JSONException("Poweramp order is required");
                }
            }
            boolean queueAdd = false;
            if (root.has("queueCapabilities") && !root.isNull("queueCapabilities")) {
                JSONObject queue = root.getJSONObject("queueCapabilities");
                queueAdd = queue.optBoolean("add", false);
            }
            return new LibrarySortCapabilities(supported, queueAdd);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid Library capabilities", exception);
        }
    }

    boolean supports(LibrarySort.Criterion criterion) {
        return criteria.contains(criterion);
    }

    boolean hasSelectableSort() {
        return criteria.size() > 1;
    }

    boolean supportsQueueAdd() {
        return queueAddSupported;
    }

    LibrarySort supportedOrDefault(LibrarySort selected) {
        return selected != null && supports(selected.criterion)
                ? selected : LibrarySort.POWERAMP;
    }
}
