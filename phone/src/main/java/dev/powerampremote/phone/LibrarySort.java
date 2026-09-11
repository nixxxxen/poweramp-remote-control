package dev.powerampremote.phone;

import java.util.Objects;

/** Phone representation of the additive API v1 Library track sort contract. */
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

        Direction initialDirection() {
            return this == DATE_ADDED || this == PLAY_COUNT
                    ? Direction.DESCENDING : Direction.ASCENDING;
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

        Direction opposite() {
            return this == ASCENDING ? DESCENDING : ASCENDING;
        }
    }

    static final LibrarySort POWERAMP = new LibrarySort(
            Criterion.DEFAULT, Direction.ASCENDING
    );

    final Criterion criterion;
    final Direction direction;

    LibrarySort(Criterion criterion, Direction direction) {
        this.criterion = Objects.requireNonNull(criterion);
        this.direction = Objects.requireNonNull(direction);
    }

    static LibrarySort forCriterion(Criterion criterion) {
        return criterion == Criterion.DEFAULT
                ? POWERAMP : new LibrarySort(criterion, criterion.initialDirection());
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
