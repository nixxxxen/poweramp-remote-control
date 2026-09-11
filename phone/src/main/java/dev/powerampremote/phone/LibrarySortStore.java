package dev.powerampremote.phone;

import android.content.Context;
import android.content.SharedPreferences;

/** Private bounded Phone preferences for one sort selection per logical Library view. */
final class LibrarySortStore {
    private static final String PREFERENCES_NAME = "library_track_sort";
    private final SharedPreferences preferences;

    LibrarySortStore(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    LibrarySortStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Missing preferences");
        this.preferences = preferences;
    }

    LibrarySort load(LibrarySortView view) {
        if (view == null) return LibrarySort.POWERAMP;
        String criterion = preferences.getString(criterionKey(view), null);
        String direction = preferences.getString(directionKey(view), null);
        if (criterion == null || direction == null) return LibrarySort.POWERAMP;
        try {
            return new LibrarySort(
                    LibrarySort.Criterion.fromWireName(criterion),
                    LibrarySort.Direction.fromWireName(direction)
            );
        } catch (IllegalArgumentException exception) {
            return LibrarySort.POWERAMP;
        }
    }

    boolean save(LibrarySortView view, LibrarySort sort) {
        if (view == null || sort == null) return false;
        return preferences.edit()
                .putString(criterionKey(view), sort.criterion.wireName)
                .putString(directionKey(view), sort.direction.wireName)
                .commit();
    }

    static String criterionKey(LibrarySortView view) {
        return "criterion_" + view.key;
    }

    static String directionKey(LibrarySortView view) {
        return "direction_" + view.key;
    }
}
