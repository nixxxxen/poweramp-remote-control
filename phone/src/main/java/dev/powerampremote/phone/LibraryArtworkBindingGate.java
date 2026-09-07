package dev.powerampremote.phone;

import java.util.Objects;

/** Per-row binding token that rejects an image after its ListView row is reused. */
final class LibraryArtworkBindingGate {
    static final class Request {
        final long serial;
        final LibraryArtworkKey key;

        private Request(long serial, LibraryArtworkKey key) {
            this.serial = serial;
            this.key = key;
        }
    }

    private long serial;
    private LibraryArtworkKey currentKey;

    Request bind(LibraryArtworkKey key) {
        if (!Objects.equals(currentKey, key)) {
            currentKey = key;
            serial++;
        }
        return new Request(serial, key);
    }

    boolean accepts(Request request, LibraryArtworkKey key) {
        return request != null
                && request.serial == serial
                && Objects.equals(request.key, key)
                && Objects.equals(currentKey, key);
    }
}
