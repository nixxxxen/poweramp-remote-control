package dev.powerampremote.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Strict additive API v1 sort selection for complete Library track snapshots. */
final class LibrarySort {
    enum Criterion {
        DEFAULT("default"),
        TITLE("title"),
        ALBUM("album"),
        ARTIST("artist"),
        DURATION("duration"),
        DATE_ADDED("date_added"),
        PLAY_COUNT("play_count");

        final String wireName;

        Criterion(String wireName) {
            this.wireName = wireName;
        }

        static Criterion fromWireName(String value) {
            for (Criterion criterion : values()) {
                if (criterion.wireName.equals(value)) return criterion;
            }
            throw new IllegalArgumentException("Unsupported Library sort criterion");
        }
    }

    enum Direction {
        ASCENDING("asc"),
        DESCENDING("desc");

        final String wireName;

        Direction(String wireName) {
            this.wireName = wireName;
        }

        static Direction fromWireName(String value) {
            for (Direction direction : values()) {
                if (direction.wireName.equals(value)) return direction;
            }
            throw new IllegalArgumentException("Unsupported Library sort direction");
        }
    }

    static final LibrarySort POWERAMP = new LibrarySort(
            Criterion.DEFAULT, Direction.ASCENDING
    );
    static final List<Criterion> SUPPORTED_CRITERIA = Collections.unmodifiableList(
            Arrays.asList(Criterion.values())
    );

    final Criterion criterion;
    final Direction direction;

    LibrarySort(Criterion criterion, Direction direction) {
        this.criterion = Objects.requireNonNull(criterion);
        this.direction = Objects.requireNonNull(direction);
    }

    static LibrarySort parse(String criterion, String direction) {
        if (criterion == null && direction == null) return POWERAMP;
        if (criterion == null || direction == null) {
            throw new IllegalArgumentException("Sort and direction must be supplied together");
        }
        return new LibrarySort(
                Criterion.fromWireName(criterion),
                Direction.fromWireName(direction)
        );
    }

    String key() {
        return criterion.wireName + '\n' + direction.wireName;
    }

    boolean isPowerampOrder() {
        return criterion == Criterion.DEFAULT;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof LibrarySort)) return false;
        LibrarySort other = (LibrarySort) value;
        return criterion == other.criterion && direction == other.direction;
    }

    @Override
    public int hashCode() {
        return 31 * criterion.hashCode() + direction.hashCode();
    }
}
