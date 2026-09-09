package dev.powerampremote.phone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Strict bounded parser for the typed grouped Search response. */
final class CategorizedSearchParser {
    static CategorizedSearchResult parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String query = root.getString("query");
            if (query.isEmpty() || query.length() > 160 || !query.equals(query.trim())) {
                throw new JSONException("Invalid Search query");
            }
            int limit = root.getInt("limit");
            if (limit < 1 || limit > 100) {
                throw new JSONException("Invalid Search limit");
            }
            String trackMatch = root.getString("trackMatch");
            if (!"none".equals(trackMatch) && !"exact".equals(trackMatch)
                    && !"partial".equals(trackMatch)) {
                throw new JSONException("Invalid track match mode");
            }
            JSONArray values = root.getJSONArray("sections");
            if (values.length() > CategorizedSearchResult.SectionType.values().length) {
                throw new JSONException("Too many Search sections");
            }
            List<CategorizedSearchResult.Section> sections = new ArrayList<>(values.length());
            int lastSection = -1;
            boolean hasTracks = false;
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                CategorizedSearchResult.SectionType type;
                try {
                    type = CategorizedSearchResult.SectionType.fromWireName(
                            value.getString("type")
                    );
                } catch (IllegalArgumentException exception) {
                    throw new JSONException("Unknown Search section");
                }
                if (type.ordinal() <= lastSection) {
                    throw new JSONException("Search sections are out of order");
                }
                lastSection = type.ordinal();
                JSONArray itemValues = value.getJSONArray("items");
                if (itemValues.length() < 1 || itemValues.length() > limit) {
                    throw new JSONException("Invalid Search section size");
                }
                List<LibraryItem> items = new ArrayList<>(itemValues.length());
                for (int itemIndex = 0; itemIndex < itemValues.length(); itemIndex++) {
                    LibraryItem item = LibraryPageParser.parseItem(
                            itemValues.getJSONObject(itemIndex)
                    );
                    if (!type.itemType.equals(item.type)) {
                        throw new JSONException("Wrong item type for Search section");
                    }
                    items.add(item);
                }
                boolean truncated = value.getBoolean("truncated");
                sections.add(new CategorizedSearchResult.Section(type, items, truncated));
                hasTracks |= type == CategorizedSearchResult.SectionType.TRACKS;
            }
            if (hasTracks == "none".equals(trackMatch)) {
                throw new JSONException("Track match mode disagrees with sections");
            }
            return new CategorizedSearchResult(query, limit, trackMatch, sections);
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid categorized Search response", exception);
        }
    }

    private CategorizedSearchParser() {
    }
}
