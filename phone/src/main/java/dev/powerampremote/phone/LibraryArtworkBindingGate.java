package dev.powerampremote.phone;

import java.util.Objects;

/** Per-row binding token that rejects an image after its ListView row is reused. */
final class LibraryArtworkBindingGate {
    static final class Request {
        final long serial;
        final Object key;

        private Request(long serial, Object key) {
            this.serial = serial;
            this.key = key;
        }
    }

    private long serial;
    private Object currentKey;
    private int lifecycle;
    private long pendingSerial = -1L;
    private boolean artworkDisplayed;

    Request bind(Object key) {
        return bind(key, lifecycle);
    }

    Request bind(Object key, int lifecycle) {
        if (!Objects.equals(currentKey, key)) {
            currentKey = key;
            artworkDisplayed = false;
            serial++;
        }
        if (this.lifecycle != lifecycle) {
            this.lifecycle = lifecycle;
            serial++;
        }
        return new Request(serial, key);
    }

    boolean begin(Request request) {
        if (!accepts(request, currentKey) || currentKey == null || pendingSerial == serial) {
            return false;
        }
        pendingSerial = serial;
        return true;
    }

    boolean complete(Request request, Object key, boolean loaded) {
        if (!accepts(request, key)) return false;
        pendingSerial = -1L;
        artworkDisplayed |= loaded;
        return true;
    }

    boolean artworkDisplayed() {
        return artworkDisplayed;
    }

    boolean accepts(Request request, Object key) {
        return request != null
                && request.serial == serial
                && Objects.equals(request.key, key)
                && Objects.equals(currentKey, key);
    }
}
