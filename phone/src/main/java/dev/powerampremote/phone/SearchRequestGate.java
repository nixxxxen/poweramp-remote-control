package dev.powerampremote.phone;

/** Rejects debounced or network results from an older query or connection generation. */
final class SearchRequestGate {
    static final class Request {
        final long serial;
        final int connectionGeneration;
        final String query;

        private Request(long serial, int connectionGeneration, String query) {
            this.serial = serial;
            this.connectionGeneration = connectionGeneration;
            this.query = query;
        }
    }

    private long serial;

    Request begin(String query, int connectionGeneration) {
        return new Request(++serial, connectionGeneration, query);
    }

    void invalidate() {
        serial++;
    }

    boolean accepts(Request request, String currentQuery, int currentConnectionGeneration) {
        return request != null
                && request.serial == serial
                && request.connectionGeneration == currentConnectionGeneration
                && request.query.equals(currentQuery);
    }
}
