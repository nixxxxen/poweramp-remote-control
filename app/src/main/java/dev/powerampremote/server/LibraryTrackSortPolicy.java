package dev.powerampremote.server;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic whole-snapshot ordering for track and playlist-entry rows. */
final class LibraryTrackSortPolicy {
    static List<LibraryItem> sorted(List<LibraryItem> source, LibrarySort sort) {
        if (source == null || source.isEmpty() || sort == null || sort.isPowerampOrder()) {
            return source == null ? Collections.emptyList() : source;
        }
        ArrayList<LibraryItem> result = new ArrayList<>(source);
        result.sort(comparator(sort));
        return Collections.unmodifiableList(result);
    }

    static Comparator<LibraryItem> comparator(LibrarySort sort) {
        if (sort == null || sort.isPowerampOrder()) return (left, right) -> 0;
        return (left, right) -> {
            int primary = comparePrimary(left, right, sort);
            if (primary != 0) return primary;

            int compared = compareText(left.title, right.title, LibrarySort.Direction.ASCENDING);
            if (compared != 0) return compared;
            compared = compareText(left.album, right.album, LibrarySort.Direction.ASCENDING);
            if (compared != 0) return compared;
            compared = compareText(left.artist, right.artist, LibrarySort.Direction.ASCENDING);
            if (compared != 0) return compared;
            compared = compareNumber(
                    left.durationMilliseconds,
                    right.durationMilliseconds,
                    LibrarySort.Direction.ASCENDING
            );
            if (compared != 0) return compared;
            compared = compareNumber(
                    left.dateAddedEpochSeconds,
                    right.dateAddedEpochSeconds,
                    LibrarySort.Direction.ASCENDING
            );
            if (compared != 0) return compared;
            compared = compareNumber(
                    left.playCount,
                    right.playCount,
                    LibrarySort.Direction.ASCENDING
            );
            if (compared != 0) return compared;
            compared = Long.compare(uniqueRowId(left), uniqueRowId(right));
            if (compared != 0) return compared;
            return Long.compare(left.id, right.id);
        };
    }

    private static int comparePrimary(
            LibraryItem left,
            LibraryItem right,
            LibrarySort sort
    ) {
        switch (sort.criterion) {
            case TITLE:
                return compareText(left.title, right.title, sort.direction);
            case ALBUM:
                return compareText(left.album, right.album, sort.direction);
            case ARTIST:
                return compareText(left.artist, right.artist, sort.direction);
            case DURATION:
                return compareNumber(
                        left.durationMilliseconds, right.durationMilliseconds, sort.direction
                );
            case DATE_ADDED:
                return compareNumber(
                        left.dateAddedEpochSeconds, right.dateAddedEpochSeconds, sort.direction
                );
            case PLAY_COUNT:
                return compareNumber(left.playCount, right.playCount, sort.direction);
            case DEFAULT:
            default:
                return 0;
        }
    }

    /** Null/blank values stay last for both directions. */
    private static int compareText(
            String left,
            String right,
            LibrarySort.Direction direction
    ) {
        String leftKey = normalizedKey(left);
        String rightKey = normalizedKey(right);
        if (leftKey == null) return rightKey == null ? 0 : 1;
        if (rightKey == null) return -1;
        int compared = leftKey.compareTo(rightKey);
        if (compared == 0) {
            compared = normalizedOriginal(left).compareTo(normalizedOriginal(right));
        }
        if (compared == 0) compared = left.compareTo(right);
        return direction == LibrarySort.Direction.DESCENDING ? -compared : compared;
    }

    /** Null values stay last for both directions. */
    private static int compareNumber(
            Long left,
            Long right,
            LibrarySort.Direction direction
    ) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        int compared = Long.compare(left, right);
        return direction == LibrarySort.Direction.DESCENDING ? -compared : compared;
    }

    private static String normalizedKey(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        String decomposed = Normalizer.normalize(
                clean.toLowerCase(Locale.ROOT), Normalizer.Form.NFD
        );
        StringBuilder key = new StringBuilder(decomposed.length());
        boolean whitespace = false;
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) continue;
            if (Character.isWhitespace(codePoint)) {
                whitespace = key.length() > 0;
                continue;
            }
            if (whitespace) key.append(' ');
            whitespace = false;
            key.appendCodePoint(codePoint);
        }
        return key.length() == 0 ? null : key.toString();
    }

    private static String normalizedOriginal(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static long uniqueRowId(LibraryItem item) {
        return item.entryId == null ? item.id : item.entryId;
    }

    private LibraryTrackSortPolicy() { }
}
