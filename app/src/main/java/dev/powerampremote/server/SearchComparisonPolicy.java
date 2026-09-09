package dev.powerampremote.server;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, locale-stable comparison and bounded typo policy for categorized Search. */
final class SearchComparisonPolicy {
    enum MatchClass {
        EXACT,
        PREFIX,
        SUBSTRING,
        FUZZY,
        NONE
    }

    static final class Match {
        final MatchClass matchClass;
        final int distance;

        Match(MatchClass matchClass, int distance) {
            this.matchClass = matchClass;
            this.distance = distance;
        }

        boolean deterministic() {
            return matchClass == MatchClass.EXACT
                    || matchClass == MatchClass.PREFIX
                    || matchClass == MatchClass.SUBSTRING;
        }
    }

    private static final Pattern STANDALONE_AND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])and(?![\\p{L}\\p{N}])"
    );
    private static final Pattern STANDALONE_AMPERSAND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])&(?![\\p{L}\\p{N}])"
    );
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    static String comparisonKey(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(
                value.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );
        StringBuilder clean = new StringBuilder(decomposed.length());
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            clean.appendCodePoint(isDash(codePoint) ? '-' : codePoint);
        }
        String ampersandKey = STANDALONE_AND.matcher(clean).replaceAll(" & ");
        ampersandKey = STANDALONE_AMPERSAND.matcher(ampersandKey).replaceAll(" & ");
        return REPEATED_WHITESPACE.matcher(ampersandKey).replaceAll(" ").trim();
    }

    static Match match(String query, String candidate, boolean allowFuzzy) {
        String queryKey = comparisonKey(query);
        String candidateKey = comparisonKey(candidate);
        if (queryKey.isEmpty() || candidateKey.isEmpty()) {
            return new Match(MatchClass.NONE, Integer.MAX_VALUE);
        }
        if (candidateKey.equals(queryKey)) {
            return new Match(MatchClass.EXACT, 0);
        }
        if (candidateKey.startsWith(queryKey)) {
            return new Match(MatchClass.PREFIX, 0);
        }
        if (candidateKey.contains(queryKey)
                || containsEveryToken(candidateKey, queryKey)
                || containsEveryToken(weakTokenKey(candidateKey), weakTokenKey(queryKey))) {
            return new Match(MatchClass.SUBSTRING, 0);
        }
        int maximumDistance = maximumFuzzyDistance(queryKey);
        if (!allowFuzzy || maximumDistance == 0) {
            return new Match(MatchClass.NONE, Integer.MAX_VALUE);
        }
        int distance = damerauLevenshtein(queryKey, candidateKey, maximumDistance);
        return distance <= maximumDistance
                ? new Match(MatchClass.FUZZY, distance)
                : new Match(MatchClass.NONE, Integer.MAX_VALUE);
    }

    static int maximumFuzzyDistance(String query) {
        String key = comparisonKey(query);
        int length = key.codePointCount(0, key.length());
        if (length < 5) return 0;
        return length < 8 ? 1 : 2;
    }

    /**
     * Small provider-side prefilter probes. Final matching still uses {@link #match}; these only
     * avoid depending on the first bounded all-rows window when accents or typos differ.
     */
    static List<String> providerSearchProbes(String query) {
        String raw = query == null ? "" : query.trim();
        String key = comparisonKey(raw);
        LinkedHashSet<String> probes = new LinkedHashSet<>();
        if (!key.isEmpty() && !key.equalsIgnoreCase(raw)) probes.add(key);

        int parts = maximumFuzzyDistance(key) + 1;
        int[] points = key.codePoints().toArray();
        if (parts > 1 && points.length >= parts * 2) {
            for (int part = 0; part < parts; part++) {
                int start = part * points.length / parts;
                int end = (part + 1) * points.length / parts;
                String probe = new String(points, start, end - start).trim();
                if (probe.codePointCount(0, probe.length()) >= 2
                        && containsLetterOrDigit(probe)
                        && !probe.equalsIgnoreCase(raw)) {
                    probes.add(probe);
                }
            }
        }
        return new ArrayList<>(probes);
    }

    private static boolean containsEveryToken(String candidate, String query) {
        String[] queryTokens = query.split(" ");
        if (queryTokens.length < 2) return false;
        String paddedCandidate = " " + candidate + " ";
        for (String token : queryTokens) {
            if (token.isEmpty() || "&".equals(token)) continue;
            if (!paddedCandidate.contains(" " + token + " ")) return false;
        }
        return true;
    }

    private static String weakTokenKey(String value) {
        StringBuilder weak = new StringBuilder(value.length());
        boolean lastWasSpace = true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                weak.appendCodePoint(codePoint);
                lastWasSpace = false;
            } else if (!lastWasSpace) {
                weak.append(' ');
                lastWasSpace = true;
            }
        }
        int length = weak.length();
        return length > 0 && weak.charAt(length - 1) == ' '
                ? weak.substring(0, length - 1) : weak.toString();
    }

    private static int damerauLevenshtein(String left, String right, int maximum) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        if (Math.abs(leftPoints.length - rightPoints.length) > maximum) {
            return maximum + 1;
        }
        int outside = maximum + 1;
        int[] previousPrevious = new int[rightPoints.length + 1];
        int[] previous = new int[rightPoints.length + 1];
        int[] current = new int[rightPoints.length + 1];
        Arrays.fill(previousPrevious, outside);
        Arrays.fill(previous, outside);
        for (int rightIndex = 0; rightIndex <= rightPoints.length; rightIndex++) {
            if (rightIndex <= maximum) previous[rightIndex] = rightIndex;
        }
        for (int leftIndex = 1; leftIndex <= leftPoints.length; leftIndex++) {
            Arrays.fill(current, outside);
            if (leftIndex <= maximum) current[0] = leftIndex;
            int start = Math.max(1, leftIndex - maximum);
            int end = Math.min(rightPoints.length, leftIndex + maximum);
            for (int rightIndex = start; rightIndex <= end; rightIndex++) {
                int substitution = leftPoints[leftIndex - 1] == rightPoints[rightIndex - 1]
                        ? 0 : 1;
                int value = Math.min(
                        previous[rightIndex] + 1,
                        Math.min(
                                current[rightIndex - 1] + 1,
                                previous[rightIndex - 1] + substitution
                        )
                );
                if (leftIndex > 1 && rightIndex > 1
                        && leftPoints[leftIndex - 1] == rightPoints[rightIndex - 2]
                        && leftPoints[leftIndex - 2] == rightPoints[rightIndex - 1]) {
                    value = Math.min(value, previousPrevious[rightIndex - 2] + 1);
                }
                current[rightIndex] = Math.min(value, outside);
            }
            int[] recycled = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = recycled;
        }
        return previous[rightPoints.length];
    }

    private static boolean isDash(int codePoint) {
        return codePoint == '-'
                || codePoint == 0x2010
                || codePoint == 0x2011
                || codePoint == 0x2012
                || codePoint == 0x2013
                || codePoint == 0x2014
                || codePoint == 0x2015
                || codePoint == 0x2212
                || codePoint == 0xFE58
                || codePoint == 0xFE63
                || codePoint == 0xFF0D;
    }

    private static boolean containsLetterOrDigit(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private SearchComparisonPolicy() {
    }
}
