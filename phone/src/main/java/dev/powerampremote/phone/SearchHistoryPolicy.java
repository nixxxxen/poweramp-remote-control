package dev.powerampremote.phone;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure ordering, stable-normalization, deduplication, and size policy for local Search history. */
final class SearchHistoryPolicy {
    static final int MAXIMUM_ENTRIES = 20;
    private static final Pattern STANDALONE_AND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])and(?![\\p{L}\\p{N}])"
    );
    private static final Pattern STANDALONE_AMPERSAND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])&(?![\\p{L}\\p{N}])"
    );
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    static List<String> record(List<String> current, String rawQuery) {
        String query = clean(rawQuery);
        if (query == null) return sanitize(current);
        String key = normalizationKey(query);
        List<String> result = new ArrayList<>();
        result.add(query);
        if (current != null) {
            for (String existing : current) {
                String cleanExisting = clean(existing);
                if (cleanExisting == null
                        || key.equals(normalizationKey(cleanExisting))) continue;
                result.add(cleanExisting);
                if (result.size() == MAXIMUM_ENTRIES) break;
            }
        }
        return result;
    }

    static List<String> remove(List<String> current, String rawQuery) {
        String key = normalizationKey(rawQuery);
        List<String> result = new ArrayList<>();
        if (current != null) {
            for (String existing : current) {
                String cleanExisting = clean(existing);
                if (cleanExisting != null && !key.equals(normalizationKey(cleanExisting))) {
                    result.add(cleanExisting);
                }
            }
        }
        return result;
    }

    static List<String> sanitize(List<String> values) {
        List<String> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String clean = clean(value);
                if (clean == null || !keys.add(normalizationKey(clean))) continue;
                result.add(clean);
                if (result.size() == MAXIMUM_ENTRIES) break;
            }
        }
        return result;
    }

    static String normalizationKey(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(clean, Normalizer.Form.NFD);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) continue;
            normalized.appendCodePoint(isDash(codePoint) ? '-' : codePoint);
        }
        String key = STANDALONE_AND.matcher(normalized).replaceAll(" & ");
        key = STANDALONE_AMPERSAND.matcher(key).replaceAll(" & ");
        return REPEATED_WHITESPACE.matcher(key).replaceAll(" ").trim();
    }

    private static String clean(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > 160) return null;
        for (int index = 0; index < clean.length(); index++) {
            if (Character.isISOControl(clean.charAt(index))) return null;
        }
        return clean;
    }

    private static boolean isDash(int codePoint) {
        return codePoint == '-'
                || codePoint == 0x2010
                || codePoint == 0x2011
                || codePoint == 0x2012
                || codePoint == 0x2013
                || codePoint == 0x2014
                || codePoint == 0x2015;
    }

    private SearchHistoryPolicy() { }
}
