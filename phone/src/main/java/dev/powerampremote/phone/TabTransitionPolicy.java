package dev.powerampremote.phone;

/** Pure ordering contract for the four primary Phone tabs. */
final class TabTransitionPolicy {
    private static final int FIRST_TAB_INDEX = 0;
    private static final int LAST_TAB_INDEX = 3;

    enum Direction {
        NONE,
        TO_RIGHT_TAB,
        TO_LEFT_TAB
    }

    static Direction direction(int currentIndex, int targetIndex) {
        if (currentIndex < FIRST_TAB_INDEX || currentIndex > LAST_TAB_INDEX
                || targetIndex < FIRST_TAB_INDEX || targetIndex > LAST_TAB_INDEX) {
            return Direction.NONE;
        }
        if (targetIndex == currentIndex) return Direction.NONE;
        return targetIndex > currentIndex
                ? Direction.TO_RIGHT_TAB
                : Direction.TO_LEFT_TAB;
    }

    static float outgoingOffset(Direction direction, int width) {
        if (direction == Direction.TO_RIGHT_TAB) return -Math.max(width, 0);
        if (direction == Direction.TO_LEFT_TAB) return Math.max(width, 0);
        return 0f;
    }

    static float incomingOffset(Direction direction, int width) {
        return -outgoingOffset(direction, width);
    }

    private TabTransitionPolicy() { }
}
