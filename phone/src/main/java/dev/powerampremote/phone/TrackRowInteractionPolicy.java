package dev.powerampremote.phone;

/** Pure routing for a row body; child action buttons own their events separately. */
final class TrackRowInteractionPolicy {
    enum Action {
        OPEN,
        TOGGLE_SELECTION,
        NONE
    }

    private TrackRowInteractionPolicy() { }

    static Action tap(
            boolean selectionMode,
            boolean trackCapable,
            boolean selectionMutable
    ) {
        if (!selectionMode) return Action.OPEN;
        return trackCapable && selectionMutable ? Action.TOGGLE_SELECTION : Action.NONE;
    }

    static Action longPress(boolean trackCapable, boolean selectionMutable) {
        return trackCapable && selectionMutable ? Action.TOGGLE_SELECTION : Action.NONE;
    }
}
