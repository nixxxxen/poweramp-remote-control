package dev.powerampremote.server;

/** Honest result of a non-transactional Queue append. */
final class QueueAddResult {
    enum Failure {
        NONE(null),
        ITEM_NOT_FOUND("item_not_found"),
        INSERT_FAILED("insert_failed"),
        SORT_UNAVAILABLE("sort_unavailable"),
        SORT_OVERFLOW("sort_overflow"),
        RELOAD_FAILED("reload_failed"),
        POWERAMP_UNAVAILABLE("poweramp_unavailable"),
        PERMISSION_REQUIRED("poweramp_data_permission_required"),
        PROVIDER_UNAVAILABLE("poweramp_provider_unavailable"),
        PROVIDER_ERROR("poweramp_provider_error"),
        CANCELLED("cancelled"),
        SERVICE_INACTIVE("service_inactive");

        final String wireName;

        Failure(String wireName) {
            this.wireName = wireName;
        }
    }

    final int requestedCount;
    final int addedCount;
    final boolean complete;
    final Integer failedIndex;
    final Failure failure;
    final LibraryAccessState.Status accessStatus;

    QueueAddResult(
            int requestedCount,
            int addedCount,
            boolean complete,
            Integer failedIndex,
            Failure failure,
            LibraryAccessState.Status accessStatus
    ) {
        if (requestedCount < 1 || requestedCount > QueueAddRequest.MAX_ITEMS
                || addedCount < 0 || addedCount > requestedCount
                || failedIndex != null
                && (failedIndex < 0 || failedIndex >= requestedCount)
                || complete != (addedCount == requestedCount && failure == Failure.NONE)
                || complete && failedIndex != null
                || !complete && failure == Failure.NONE) {
            throw new IllegalArgumentException("Invalid Queue add result");
        }
        this.requestedCount = requestedCount;
        this.addedCount = addedCount;
        this.complete = complete;
        this.failedIndex = failedIndex;
        this.failure = failure;
        this.accessStatus = accessStatus;
    }

    static QueueAddResult complete(int requestedCount) {
        return new QueueAddResult(
                requestedCount,
                requestedCount,
                true,
                null,
                Failure.NONE,
                LibraryAccessState.Status.AVAILABLE
        );
    }
}
