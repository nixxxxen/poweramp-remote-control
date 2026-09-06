package dev.powerampremote.server;

import java.util.Objects;

/** Sanitized Poweramp ContentProvider availability exposed to UI and API clients. */
final class LibraryAccessState {
    enum Status {
        UNKNOWN("unknown"),
        AVAILABLE("available"),
        POWERAMP_MISSING("poweramp_missing"),
        PERMISSION_REQUIRED("permission_required"),
        PROVIDER_UNAVAILABLE("provider_unavailable"),
        PROVIDER_ERROR("provider_error");

        final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }
    }

    final Status status;
    final long sequence;

    LibraryAccessState(Status status, long sequence) {
        this.status = Objects.requireNonNull(status);
        this.sequence = sequence;
    }

    boolean permissionRequestAvailable() {
        return status == Status.PERMISSION_REQUIRED;
    }
}
