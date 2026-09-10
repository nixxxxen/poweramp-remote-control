package dev.powerampremote.server;

/** Pure parser for an explicit spaced artist/title separator. */
final class StructuredSearchQuery {
    private static final String DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2015";

    final String original;
    final String artist;
    final String title;

    private StructuredSearchQuery(String original, String artist, String title) {
        this.original = original;
        this.artist = artist;
        this.title = title;
    }

    static StructuredSearchQuery parse(String rawQuery) {
        String query = PowerampLibraryContract.validSearchQuery(rawQuery);
        for (int index = 1; index + 1 < query.length(); index++) {
            if (DASHES.indexOf(query.charAt(index)) < 0
                    || !Character.isWhitespace(query.charAt(index - 1))
                    || !Character.isWhitespace(query.charAt(index + 1))) {
                continue;
            }
            String left = query.substring(0, index).trim();
            String right = query.substring(index + 1).trim();
            if (!left.isEmpty() && !right.isEmpty()) {
                return new StructuredSearchQuery(query, left, right);
            }
        }
        return null;
    }
}
