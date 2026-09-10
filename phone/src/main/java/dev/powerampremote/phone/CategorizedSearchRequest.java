package dev.powerampremote.phone;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Phone allowlist for the additive typed grouped Search route. */
final class CategorizedSearchRequest {
    private static final int SECTION_LIMIT = 25;
    private final String query;
    private final CategorizedSearchResult.SectionType section;
    private final String pageToken;

    private CategorizedSearchRequest(
            String query,
            CategorizedSearchResult.SectionType section,
            String pageToken
    ) {
        this.query = query;
        this.section = section;
        this.pageToken = pageToken;
    }

    static CategorizedSearchRequest create(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty() || query.length() > 160) {
            throw new IllegalArgumentException("Invalid Search query");
        }
        return new CategorizedSearchRequest(query, null, null);
    }

    static CategorizedSearchRequest section(
            String rawQuery,
            CategorizedSearchResult.SectionType section,
            String pageToken
    ) {
        CategorizedSearchRequest initial = create(rawQuery);
        if (section == null) throw new IllegalArgumentException("Missing Search section");
        if (pageToken != null && !pageToken.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("Invalid Search page token");
        }
        return new CategorizedSearchRequest(initial.query, section, pageToken);
    }

    String path() {
        try {
            StringBuilder path = new StringBuilder("/api/v1/search/grouped?q=")
                    .append(URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
                    .append("&limit=")
                    .append(SECTION_LIMIT);
            if (section != null) {
                path.append("&section=").append(section.wireName);
            }
            if (pageToken != null) {
                path.append("&pageToken=").append(pageToken);
            }
            return path.toString();
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }

    boolean isContinuation() {
        return pageToken != null;
    }
}
