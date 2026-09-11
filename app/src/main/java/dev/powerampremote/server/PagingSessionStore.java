package dev.powerampremote.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Bounded, expiring server-owned snapshots behind opaque continuation tokens. */
final class PagingSessionStore<T> implements AutoCloseable {
    static final class Page<T> {
        final List<T> items;
        final int offset;
        final String nextPageToken;
        final Object metadata;

        Page(List<T> items, int offset, String nextPageToken, Object metadata) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.offset = offset;
            this.nextPageToken = nextPageToken;
            this.metadata = metadata;
        }
    }

    static final class InvalidTokenException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final int maximumSessions;
    private final long ttlMilliseconds;
    private final LongSupplier clock;
    private final SecureRandom random;
    private final Runnable sessionClosed;
    private final LinkedHashMap<Long, Session<T>> sessions =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Token> tokens = new HashMap<>();
    private long nextSessionId;

    PagingSessionStore(
            int maximumSessions,
            long ttlMilliseconds,
            LongSupplier clock,
            SecureRandom random
    ) {
        this(maximumSessions, ttlMilliseconds, clock, random, null);
    }

    PagingSessionStore(
            int maximumSessions,
            long ttlMilliseconds,
            LongSupplier clock,
            SecureRandom random,
            Runnable sessionClosed
    ) {
        if (maximumSessions < 1 || ttlMilliseconds < 1L) {
            throw new IllegalArgumentException("Invalid paging-session bounds");
        }
        this.maximumSessions = maximumSessions;
        this.ttlMilliseconds = ttlMilliseconds;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        this.sessionClosed = sessionClosed;
    }

    synchronized Page<T> firstPage(
            String queryKey,
            List<T> values,
            int pageSize,
            Object metadata
    ) {
        Objects.requireNonNull(queryKey);
        Objects.requireNonNull(values);
        validatePageSize(pageSize);
        pruneExpired();
        if (values.size() <= pageSize) {
            return new Page<>(values, 0, null, metadata);
        }
        while (sessions.size() >= maximumSessions) {
            Iterator<Long> iterator = sessions.keySet().iterator();
            if (!iterator.hasNext()) break;
            closeSession(iterator.next());
        }
        long sessionId = ++nextSessionId;
        Session<T> session = new Session<>(
                sessionId,
                queryKey,
                new ArrayList<>(values),
                metadata,
                clock.getAsLong() + ttlMilliseconds
        );
        sessions.put(sessionId, session);
        return page(session, 0, pageSize);
    }

    synchronized Page<T> nextPage(String token, String queryKey, int pageSize)
            throws InvalidTokenException {
        Objects.requireNonNull(queryKey);
        validatePageSize(pageSize);
        pruneExpired();
        Token continuation = tokens.get(token);
        if (continuation == null) throw new InvalidTokenException();
        Session<T> session = sessions.get(continuation.sessionId);
        if (session == null || !session.queryKey.equals(queryKey)) {
            throw new InvalidTokenException();
        }
        session.expiresAt = clock.getAsLong() + ttlMilliseconds;
        return page(session, continuation.offset, pageSize);
    }

    synchronized void discard(String token) {
        Token continuation = tokens.get(token);
        if (continuation != null) closeSession(continuation.sessionId);
    }

    synchronized void discardMatching(Predicate<String> queryKeyPredicate) {
        Objects.requireNonNull(queryKeyPredicate);
        List<Long> matching = new ArrayList<>();
        for (Session<T> session : sessions.values()) {
            if (queryKeyPredicate.test(session.queryKey)) matching.add(session.id);
        }
        for (Long id : matching) closeSession(id);
    }

    synchronized int activeSessions() {
        pruneExpired();
        return sessions.size();
    }

    @Override
    public synchronized void close() {
        List<Long> ids = new ArrayList<>(sessions.keySet());
        for (Long id : ids) closeSession(id);
        tokens.clear();
    }

    private Page<T> page(Session<T> session, int offset, int pageSize) {
        if (offset < 0 || offset >= session.values.size()) {
            throw new IllegalStateException("Invalid stored continuation offset");
        }
        int end = Math.min(session.values.size(), offset + pageSize);
        List<T> pageValues = new ArrayList<>(session.values.subList(offset, end));
        if (end >= session.values.size()) {
            Object metadata = session.metadata;
            closeSession(session.id);
            return new Page<>(pageValues, offset, null, metadata);
        }
        String nextToken = session.tokensByOffset.get(end);
        if (nextToken == null) {
            nextToken = newToken();
            session.tokensByOffset.put(end, nextToken);
            tokens.put(nextToken, new Token(session.id, end));
        }
        return new Page<>(pageValues, offset, nextToken, session.metadata);
    }

    private String newToken() {
        byte[] bytes = new byte[18];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tokens.containsKey(token));
        return token;
    }

    private void pruneExpired() {
        long now = clock.getAsLong();
        List<Long> expired = new ArrayList<>();
        for (Session<T> session : sessions.values()) {
            if (session.expiresAt <= now) expired.add(session.id);
        }
        for (Long id : expired) closeSession(id);
    }

    private void closeSession(long sessionId) {
        Session<T> session = sessions.remove(sessionId);
        if (session == null) return;
        for (String token : session.tokensByOffset.values()) tokens.remove(token);
        session.tokensByOffset.clear();
        session.values.clear();
        if (sessionClosed != null) sessionClosed.run();
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("Invalid page size");
    }

    private static final class Session<T> {
        final long id;
        final String queryKey;
        final ArrayList<T> values;
        final Object metadata;
        final Map<Integer, String> tokensByOffset = new HashMap<>();
        long expiresAt;

        Session(
                long id,
                String queryKey,
                ArrayList<T> values,
                Object metadata,
                long expiresAt
        ) {
            this.id = id;
            this.queryKey = queryKey;
            this.values = values;
            this.metadata = metadata;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Token {
        final long sessionId;
        final int offset;

        Token(long sessionId, int offset) {
            this.sessionId = sessionId;
            this.offset = offset;
        }
    }
}
