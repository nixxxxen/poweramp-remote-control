package dev.powerampremote.phone;

import org.json.JSONException;
import org.json.JSONObject;

/** Honest non-transactional result returned even for a partial append. */
final class QueueAddResult {
    final int requestedCount;
    final int addedCount;
    final boolean complete;
    final Integer failedIndex;
    final String failure;

    private QueueAddResult(
            int requestedCount,
            int addedCount,
            boolean complete,
            Integer failedIndex,
            String failure
    ) {
        this.requestedCount = requestedCount;
        this.addedCount = addedCount;
        this.complete = complete;
        this.failedIndex = failedIndex;
        this.failure = failure;
    }

    static QueueAddResult parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            int requested = root.getInt("requestedCount");
            int added = root.getInt("addedCount");
            boolean complete = root.getBoolean("complete");
            Integer failed = root.isNull("failedIndex")
                    ? null : root.getInt("failedIndex");
            String failure = root.isNull("failure")
                    ? null : root.getString("failure");
            root.getString("status");
            if (requested < 1 || requested > QueueAddRequest.MAX_ITEMS
                    || added < 0 || added > requested
                    || failed != null && (failed < 0 || failed >= requested)
                    || complete != (added == requested && failure == null)
                    || complete && failed != null
                    || !complete && failure == null) {
                throw new JSONException("Inconsistent Queue add result");
            }
            return new QueueAddResult(requested, added, complete, failed, failure);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid Queue add response", exception);
        }
    }
}
