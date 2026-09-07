package dev.powerampremote.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded service-lifetime cache for derived category-to-track-artwork mappings. */
final class RepresentativeArtworkCache {
    static final int MAXIMUM_ENTRIES = 256;
    static final long SELECTED_MAXIMUM_AGE_MILLISECONDS = 6L * 60L * 60L * 1000L;
    static final long MISSING_RETRY_MILLISECONDS = 10L * 60L * 1000L;
    static final long TRANSIENT_RETRY_MILLISECONDS = 15L * 1000L;

    enum State { SELECTED, MISSING, TRANSIENT_FAILURE }

    static final class Mapping {
        final State state;
        final LibraryArtworkKey artworkKey;

        private Mapping(State state, LibraryArtworkKey artworkKey) {
            this.state = state;
            this.artworkKey = artworkKey;
        }
    }

    private static final class TimedMapping {
        final Mapping mapping;
        final long expiresAtMilliseconds;

        TimedMapping(Mapping mapping, long expiresAtMilliseconds) {
            this.mapping = mapping;
            this.expiresAtMilliseconds = expiresAtMilliseconds;
        }
    }

    private static final class CandidateEntry {
        final List<LibraryArtworkKey> candidates;
        final long expiresAtMilliseconds;

        CandidateEntry(List<LibraryArtworkKey> candidates, long expiresAtMilliseconds) {
            this.candidates = candidates;
            this.expiresAtMilliseconds = expiresAtMilliseconds;
        }
    }

    private final LinkedHashMap<RepresentativeArtworkKey, TimedMapping> mappings =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<RepresentativeArtworkKey, CandidateEntry> candidatePages =
            new LinkedHashMap<>(16, 0.75f, true);

    synchronized Mapping get(RepresentativeArtworkKey key, long nowMilliseconds) {
        TimedMapping entry = mappings.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMilliseconds <= nowMilliseconds) {
            mappings.remove(key);
            return null;
        }
        return entry.mapping;
    }

    synchronized void selected(
            RepresentativeArtworkKey key,
            LibraryArtworkKey artworkKey,
            long nowMilliseconds
    ) {
        if (key == null || artworkKey == null || !key.serverId.equals(artworkKey.serverId)) {
            throw new IllegalArgumentException("Mismatched representative artwork mapping");
        }
        putMapping(
                key,
                new Mapping(State.SELECTED, artworkKey),
                nowMilliseconds + SELECTED_MAXIMUM_AGE_MILLISECONDS
        );
    }

    synchronized void missing(RepresentativeArtworkKey key, long nowMilliseconds) {
        putMapping(
                key,
                new Mapping(State.MISSING, null),
                nowMilliseconds + MISSING_RETRY_MILLISECONDS
        );
    }

    synchronized void transientFailure(RepresentativeArtworkKey key, long nowMilliseconds) {
        putMapping(
                key,
                new Mapping(State.TRANSIENT_FAILURE, null),
                nowMilliseconds + TRANSIENT_RETRY_MILLISECONDS
        );
    }

    synchronized void invalidate(RepresentativeArtworkKey key) {
        mappings.remove(key);
    }

    synchronized void rememberCandidates(
            RepresentativeArtworkKey key,
            List<LibraryArtworkKey> candidates,
            long nowMilliseconds
    ) {
        if (key == null || candidates == null
                || candidates.size() > RepresentativeArtworkProbe.MAXIMUM_TRACKS) {
            throw new IllegalArgumentException("Invalid representative artwork candidates");
        }
        ArrayList<LibraryArtworkKey> copy = new ArrayList<>(candidates.size());
        for (LibraryArtworkKey candidate : candidates) {
            if (candidate == null || !key.serverId.equals(candidate.serverId)) {
                throw new IllegalArgumentException("Mismatched representative artwork candidate");
            }
            copy.add(candidate);
        }
        candidatePages.put(
                key,
                new CandidateEntry(
                        Collections.unmodifiableList(copy),
                        nowMilliseconds + SELECTED_MAXIMUM_AGE_MILLISECONDS
                )
        );
        TimedMapping current = mappings.get(key);
        if (current != null
                && current.mapping.state == State.SELECTED
                && !copy.contains(current.mapping.artworkKey)) {
            mappings.remove(key);
        }
        trim(candidatePages);
    }

    synchronized List<LibraryArtworkKey> candidates(
            RepresentativeArtworkKey key,
            long nowMilliseconds
    ) {
        CandidateEntry entry = candidatePages.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMilliseconds <= nowMilliseconds) {
            candidatePages.remove(key);
            return null;
        }
        return entry.candidates;
    }

    synchronized void clear() {
        mappings.clear();
        candidatePages.clear();
    }

    synchronized int mappingCount() {
        return mappings.size();
    }

    private void putMapping(
            RepresentativeArtworkKey key,
            Mapping mapping,
            long expiresAtMilliseconds
    ) {
        if (key == null) throw new IllegalArgumentException("Missing representative artwork key");
        mappings.put(key, new TimedMapping(mapping, expiresAtMilliseconds));
        trim(mappings);
    }

    private static <V> void trim(LinkedHashMap<RepresentativeArtworkKey, V> entries) {
        while (entries.size() > MAXIMUM_ENTRIES) {
            RepresentativeArtworkKey eldest = entries.entrySet().iterator().next().getKey();
            entries.remove(eldest);
        }
    }
}
