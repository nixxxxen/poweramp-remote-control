package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded, ordered probe of the first direct tracks that can supply category artwork. */
final class RepresentativeArtworkProbe {
    static final int MAXIMUM_TRACKS = 6;

    enum Outcome { AVAILABLE, MISSING, TRANSIENT_FAILURE }
    enum Decision { SELECTED, TRY_NEXT, EXHAUSTED, STOPPED, STALE }

    private final List<LibraryArtworkKey> candidates;
    private int index;

    RepresentativeArtworkProbe(List<LibraryArtworkKey> candidates) {
        if (candidates == null || candidates.size() > MAXIMUM_TRACKS) {
            throw new IllegalArgumentException("Invalid representative artwork candidates");
        }
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    static List<LibraryArtworkKey> candidates(
            String serverId,
            List<LibraryItem> items
    ) {
        if (!PairingCredentials.isValidServerId(serverId) || items == null) {
            throw new IllegalArgumentException("Invalid representative artwork source");
        }
        Set<LibraryArtworkKey> candidates = new LinkedHashSet<>();
        int examined = 0;
        for (LibraryItem item : items) {
            if (examined >= MAXIMUM_TRACKS) break;
            examined++;
            if (item == null || !isTrack(item.type) || item.artworkPath == null) continue;
            try {
                candidates.add(LibraryArtworkKey.create(serverId, item.artworkPath));
            } catch (IllegalArgumentException ignored) {
                // Parsed API items are already allowlisted; tolerate a defensive invalid value.
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    LibraryArtworkKey current() {
        return index >= candidates.size() ? null : candidates.get(index);
    }

    Decision apply(LibraryArtworkKey candidate, Outcome outcome) {
        LibraryArtworkKey current = current();
        if (current == null || !current.equals(candidate) || outcome == null) {
            return Decision.STALE;
        }
        if (outcome == Outcome.AVAILABLE) return Decision.SELECTED;
        if (outcome == Outcome.TRANSIENT_FAILURE) return Decision.STOPPED;
        index++;
        return current() == null ? Decision.EXHAUSTED : Decision.TRY_NEXT;
    }

    private static boolean isTrack(String type) {
        return "track".equals(type)
                || "playlist_entry".equals(type)
                || "queue_entry".equals(type);
    }
}
