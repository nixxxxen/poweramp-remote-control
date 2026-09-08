package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TabTransitionPolicyTest {
    @Test
    public void fixedTabOrderProducesExpectedDirection() {
        assertEquals(0, BottomNavigation.Tab.PLAYER.index);
        assertEquals(1, BottomNavigation.Tab.LIBRARY.index);
        assertEquals(2, BottomNavigation.Tab.SEARCH.index);
        assertEquals(3, BottomNavigation.Tab.SETTINGS.index);
        assertEquals(
                TabTransitionPolicy.Direction.TO_RIGHT_TAB,
                TabTransitionPolicy.direction(0, 1)
        );
        assertEquals(
                TabTransitionPolicy.Direction.TO_RIGHT_TAB,
                TabTransitionPolicy.direction(1, 3)
        );
        assertEquals(
                TabTransitionPolicy.Direction.TO_LEFT_TAB,
                TabTransitionPolicy.direction(3, 2)
        );
        assertEquals(
                TabTransitionPolicy.Direction.TO_LEFT_TAB,
                TabTransitionPolicy.direction(2, 0)
        );
        assertEquals(
                TabTransitionPolicy.Direction.NONE,
                TabTransitionPolicy.direction(2, 2)
        );
        assertEquals(
                TabTransitionPolicy.Direction.NONE,
                TabTransitionPolicy.direction(-1, 2)
        );
        assertEquals(
                TabTransitionPolicy.Direction.NONE,
                TabTransitionPolicy.direction(2, 4)
        );
    }

    @Test
    public void rightTabMovesOldLeftAndNewFromRight() {
        TabTransitionPolicy.Direction direction =
                TabTransitionPolicy.direction(0, 3);

        assertEquals(-400f, TabTransitionPolicy.outgoingOffset(direction, 400), 0f);
        assertEquals(400f, TabTransitionPolicy.incomingOffset(direction, 400), 0f);
    }

    @Test
    public void leftTabMovesOldRightAndNewFromLeft() {
        TabTransitionPolicy.Direction direction =
                TabTransitionPolicy.direction(3, 0);

        assertEquals(400f, TabTransitionPolicy.outgoingOffset(direction, 400), 0f);
        assertEquals(-400f, TabTransitionPolicy.incomingOffset(direction, 400), 0f);
    }
}
