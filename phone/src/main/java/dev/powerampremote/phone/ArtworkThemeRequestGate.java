package dev.powerampremote.phone;

import java.util.Objects;

/** Rejects asynchronous palette results that belong to an older track/artwork request. */
final class ArtworkThemeRequestGate {
    static final class Request {
        final long generation;
        final String identity;

        Request(long generation, String identity) {
            this.generation = generation;
            this.identity = identity;
        }
    }

    private long generation;
    private String currentIdentity;

    Request begin(String identity) {
        if (!Objects.equals(currentIdentity, identity)) {
            currentIdentity = identity;
            generation++;
        }
        return new Request(generation, currentIdentity);
    }

    Request current() {
        return new Request(generation, currentIdentity);
    }

    boolean accepts(Request request) {
        return request != null
                && request.generation == generation
                && Objects.equals(request.identity, currentIdentity);
    }

    void invalidate() {
        generation++;
        currentIdentity = null;
    }
}
