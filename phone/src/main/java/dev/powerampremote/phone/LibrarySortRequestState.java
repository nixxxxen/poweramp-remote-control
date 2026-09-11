package dev.powerampremote.phone;

/** Pure stale-response gate tied to the complete requested sort selection. */
final class LibrarySortRequestState {
    static final class Stamp {
        final long generation;
        final LibrarySort sort;

        Stamp(long generation, LibrarySort sort) {
            this.generation = generation;
            this.sort = sort;
        }
    }

    private LibrarySort sort = LibrarySort.POWERAMP;
    private long generation;

    LibrarySort sort() {
        return sort;
    }

    boolean change(LibrarySort next) {
        if (next == null || next.equals(sort)) return false;
        sort = next;
        generation++;
        return true;
    }

    Stamp begin() {
        return new Stamp(generation, sort);
    }

    boolean accepts(Stamp stamp) {
        return stamp != null && stamp.generation == generation && sort.equals(stamp.sort);
    }
}
