package dev.powerampremote.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TrackRowInteractionPolicyTest {
    @Test
    public void ordinaryTapOpensExactlyThroughThePrimaryBodyPath() {
        assertEquals(
                TrackRowInteractionPolicy.Action.OPEN,
                TrackRowInteractionPolicy.tap(false, true, true)
        );
        assertEquals(
                TrackRowInteractionPolicy.Action.OPEN,
                TrackRowInteractionPolicy.tap(false, false, true)
        );
    }

    @Test
    public void selectionTapOnlyTogglesTrackCapableRows() {
        assertEquals(
                TrackRowInteractionPolicy.Action.TOGGLE_SELECTION,
                TrackRowInteractionPolicy.tap(true, true, true)
        );
        assertEquals(
                TrackRowInteractionPolicy.Action.NONE,
                TrackRowInteractionPolicy.tap(true, false, true)
        );
        assertEquals(
                TrackRowInteractionPolicy.Action.NONE,
                TrackRowInteractionPolicy.tap(true, true, false)
        );
    }

    @Test
    public void longPressNeverOpensAndOnlySelectsMutableTracks() {
        assertEquals(
                TrackRowInteractionPolicy.Action.TOGGLE_SELECTION,
                TrackRowInteractionPolicy.longPress(true, true)
        );
        assertEquals(
                TrackRowInteractionPolicy.Action.NONE,
                TrackRowInteractionPolicy.longPress(false, true)
        );
        assertEquals(
                TrackRowInteractionPolicy.Action.NONE,
                TrackRowInteractionPolicy.longPress(true, false)
        );
    }
}
