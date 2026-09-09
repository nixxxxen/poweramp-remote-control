package dev.powerampremote.phone;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Phone allowlist for the additive typed grouped Search route. */
final class CategorizedSearchRequest {
    private static final int SECTION_LIMIT = 25;
    private final String query;

    private CategorizedSearchRequest(String query) {
        this.query = query;
    }

    static CategorizedSearchRequest create(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty() || query.length() > 160) {
            throw new IllegalArgumentException("Invalid Search query");
        }
        return new CategorizedSearchRequest(query);
    }

    String path() {
        try {
            return "/api/v1/search/grouped?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                    + "&limit=" + SECTION_LIMIT;
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }
}
