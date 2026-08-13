package dev.powerampremote.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, process-local browser sessions derived from the existing API token.
 *
 * <p>Sessions have a fixed lifetime: authenticating a request never extends it. The store is
 * intentionally not persisted, so a new application process always requires the API token again.
 */
final class BrowserSessionStore implements AutoCloseable {
    static final String COOKIE_NAME = "poweramp_remote_session";
    static final String COOKIE_PATH = "/api/v1/";
    static final long SESSION_TTL_MILLISECONDS = 12L * 60L * 60L * 1_000L;
    static final long SESSION_MAX_AGE_SECONDS = SESSION_TTL_MILLISECONDS / 1_000L;
    static final int MAX_SESSIONS = 16;

    private static final int SESSION_RANDOM_BYTES = 32;
    private static final int SESSION_ID_LENGTH = 43;
    private static final int MAX_GENERATION_ATTEMPTS = 16;

    static final class Session {
        final String id;
        final long expiresAtMilliseconds;

        private Session(String id, long expiresAtMilliseconds) {
            this.id = id;
            this.expiresAtMilliseconds = expiresAtMilliseconds;
        }
    }

    private final String expectedToken;
    private final LongSupplier monotonicClock;
    private final SecureRandom secureRandom;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private boolean closed;

    BrowserSessionStore(String expectedToken, LongSupplier monotonicClock) {
        this(expectedToken, monotonicClock, new SecureRandom());
    }

    BrowserSessionStore(
            String expectedToken,
            LongSupplier monotonicClock,
            SecureRandom secureRandom
    ) {
        if (expectedToken == null || expectedToken.isEmpty()) {
            throw new IllegalArgumentException("expectedToken must not be empty");
        }
        this.expectedToken = expectedToken;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /** Returns a new session, or {@code null} when the presented token is not an exact match. */
    synchronized Session createSession(String presentedToken) {
        if (closed || !constantTimeEquals(presentedToken, expectedToken)) {
            return null;
        }
        long now = monotonicClock.getAsLong();
        pruneExpired(now);

        String id = generateUniqueSessionId();
        while (sessions.size() >= MAX_SESSIONS) {
            Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }

        Session session = new Session(id, saturatedAdd(now, SESSION_TTL_MILLISECONDS));
        sessions.put(id, session);
        return session;
    }

    /** Returns the current session from a Cookie header, or {@code null} if absent/invalid/expired. */
    synchronized Session authenticateCookie(String cookieHeader) {
        return authenticateSessionIdLocked(extractSessionId(cookieHeader));
    }

    /** Supports re-checking the session associated with a long-lived WebSocket. */
    synchronized Session authenticateSessionId(String sessionId) {
        return authenticateSessionIdLocked(isValidSessionId(sessionId) ? sessionId : null);
    }

    synchronized boolean invalidateCookie(String cookieHeader) {
        String sessionId = extractSessionId(cookieHeader);
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    synchronized boolean invalidateSession(String sessionId) {
        return isValidSessionId(sessionId) && sessions.remove(sessionId) != null;
    }

    synchronized void clear() {
        sessions.clear();
    }

    static String setCookieHeader(Session session) {
        Objects.requireNonNull(session, "session");
        return COOKIE_NAME + '=' + session.id
                + "; Path=" + COOKIE_PATH
                + "; HttpOnly; SameSite=Strict; Max-Age=" + SESSION_MAX_AGE_SECONDS;
    }

    static String clearCookieHeader() {
        return COOKIE_NAME + '='
                + "; Path=" + COOKIE_PATH
                + "; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    /**
     * Extracts this store's cookie without URL-decoding or accepting ambiguous duplicate values.
     */
    static String extractSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        String found = null;
        String[] parts = cookieHeader.split(";", -1);
        for (String part : parts) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            int separator = candidate.indexOf('=');
            if (separator < 0) {
                if (COOKIE_NAME.equals(candidate)) {
                    return null;
                }
                continue;
            }
            String name = candidate.substring(0, separator).trim();
            if (!COOKIE_NAME.equals(name)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            String value = candidate.substring(separator + 1).trim();
            if (!isValidSessionId(value)) {
                return null;
            }
            found = value;
        }
        return found;
    }

    @Override
    public synchronized void close() {
        closed = true;
        sessions.clear();
    }

    private Session authenticateSessionIdLocked(String sessionId) {
        if (closed || sessionId == null) {
            return null;
        }
        long now = monotonicClock.getAsLong();
        pruneExpired(now);
        return sessions.get(sessionId);
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtMilliseconds <= now) {
                iterator.remove();
            }
        }
    }

    private String generateUniqueSessionId() {
        byte[] bytes = new byte[SESSION_RANDOM_BYTES];
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            secureRandom.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!sessions.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("unable to generate a unique browser session");
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean isValidSessionId(String value) {
        if (value == null || value.length() != SESSION_ID_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '-'
                    || character == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
