package dev.powerampremote.phone;

/** Matches Library rows only against identity from a confirmed complete playback snapshot. */
final class CurrentTrackMatcher {
    private CurrentTrackMatcher() { }

    static boolean matches(RemoteState state, LibraryItem item) {
        if (state == null || item == null || !state.hasTrack || state.trackRealId == null
                || item.underlyingId == null) {
            return false;
        }
        switch (item.type) {
            case "track":
                return item.underlyingId.equals(state.trackRealId);
            case "queue_entry":
                return state.sourceCategory != null
                        && state.sourceCategory == RemoteSourceCategory.QUEUE
                        && state.trackId != null
                        && item.entryId != null
                        && item.entryId.equals(state.trackId)
                        && item.underlyingId.equals(state.trackRealId);
            case "playlist_entry":
                // A playlist entry ID is meaningful only together with its playlist container.
                // The current playback snapshot does not expose that verified container identity.
                return false;
            default:
                return false;
        }
    }

    /** Small state holder used by row rendering; updating it never replaces Library rows. */
    static final class IndicatorState {
        private RemoteState confirmedState;

        void update(RemoteState state) {
            confirmedState = state;
        }

        boolean matches(LibraryItem item) {
            return CurrentTrackMatcher.matches(confirmedState, item);
        }
    }
}
