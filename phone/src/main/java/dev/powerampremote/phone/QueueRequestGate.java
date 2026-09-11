package dev.powerampremote.phone;

/** Rejects Queue pages from an older reload, continuation, or connection generation. */
final class QueueRequestGate {
    static final class Request {
        final long serial;
        final int connectionGeneration;
        final String pageToken;

        private Request(long serial, int connectionGeneration, String pageToken) {
            this.serial = serial;
            this.connectionGeneration = connectionGeneration;
            this.pageToken = pageToken;
        }
    }

    private long serial;

    Request begin(int connectionGeneration, String pageToken) {
        return new Request(++serial, connectionGeneration, pageToken);
    }

    void invalidate() {
        serial++;
    }

    boolean accepts(
            Request request,
            int currentConnectionGeneration,
            String expectedPageToken
    ) {
        return request != null
                && request.serial == serial
                && request.connectionGeneration == currentConnectionGeneration
                && same(request.pageToken, expectedPageToken);
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}
