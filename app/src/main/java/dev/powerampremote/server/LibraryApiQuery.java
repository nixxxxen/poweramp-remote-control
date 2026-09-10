package dev.powerampremote.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict parser for the small query-string surface accepted by additive library routes. */
final class LibraryApiQuery {
    private static final Pattern LIMIT_PATTERN = Pattern.compile("[1-9][0-9]{0,2}");

    final int limit;
    final String pageToken;
    final String searchQuery;
    final String searchSection;

    private LibraryApiQuery(
            int limit,
            String pageToken,
            String searchQuery,
            String searchSection
    ) {
        this.limit = limit;
        this.pageToken = pageToken;
        this.searchQuery = searchQuery;
        this.searchSection = searchSection;
    }

    static LibraryApiQuery page(String rawQuery) {
        return parse(rawQuery, false, false);
    }

    static LibraryApiQuery search(String rawQuery) {
        return parse(rawQuery, true, false);
    }

    static LibraryApiQuery categorizedSearch(String rawQuery) {
        return parse(rawQuery, true, true);
    }

    static void requireEmpty(String rawQuery) {
        if (rawQuery != null) {
            throw new IllegalArgumentException("Query parameters are not supported");
        }
    }

    private static LibraryApiQuery parse(
            String rawQuery,
            boolean search,
            boolean categorized
    ) {
        Map<String, String> parameters = parseParameters(rawQuery);
        for (String key : parameters.keySet()) {
            if (!"limit".equals(key) && !"pageToken".equals(key)
                    && !(search && "q".equals(key))
                    && !(categorized && "section".equals(key))) {
                throw new IllegalArgumentException("Unexpected query parameter");
            }
        }
        String limitValue = parameters.get("limit");
        int limit = PowerampLibraryContract.DEFAULT_PAGE_SIZE;
        if (limitValue != null) {
            if (!LIMIT_PATTERN.matcher(limitValue).matches()) {
                throw new IllegalArgumentException("Invalid limit");
            }
            try {
                limit = Integer.parseInt(limitValue);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid limit", exception);
            }
            if (limit > PowerampLibraryContract.MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid limit");
            }
        }
        String pageToken = parameters.get("pageToken");
        if (pageToken != null && !PowerampLibrarySource.isValidPageTokenSyntax(pageToken)) {
            throw new IllegalArgumentException("Invalid page token");
        }
        String query = parameters.get("q");
        if (search) {
            query = PowerampLibraryContract.validSearchQuery(query);
        } else if (query != null) {
            throw new IllegalArgumentException("Unexpected search query");
        }
        String section = parameters.get("section");
        if (!categorized && section != null) {
            throw new IllegalArgumentException("Unexpected Search section");
        }
        if (section != null) CategorizedSearch.SectionType.fromWireName(section);
        return new LibraryApiQuery(limit, pageToken, query, section);
    }

    private static Map<String, String> parseParameters(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null) {
            return parameters;
        }
        if (rawQuery.isEmpty() || rawQuery.length() > 1_024) {
            throw new IllegalArgumentException("Invalid query string");
        }
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator != pair.lastIndexOf('=')) {
                throw new IllegalArgumentException("Invalid query parameter");
            }
            String key = decode(pair.substring(0, separator));
            String value = decode(pair.substring(separator + 1));
            if (key.isEmpty() || value.isEmpty() || parameters.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate or empty query parameter");
            }
        }
        return parameters;
    }

    private static String decode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%') {
                if (index + 2 >= value.length()) {
                    throw new IllegalArgumentException("Malformed percent escape");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Malformed percent escape");
                }
                bytes.write((high << 4) | low);
                index += 2;
            } else if (character == '+') {
                bytes.write(' ');
            } else if (character > 0x7F || Character.isISOControl(character)) {
                throw new IllegalArgumentException("Invalid query character");
            } else {
                bytes.write(character);
            }
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 query", exception);
        }
    }

    private LibraryApiQuery() {
        limit = 0;
        pageToken = null;
        searchQuery = null;
        searchSection = null;
    }
}
