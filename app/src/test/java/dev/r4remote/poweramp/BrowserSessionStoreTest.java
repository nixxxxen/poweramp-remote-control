package dev.r4remote.poweramp;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BrowserSessionStoreTest {
    private static final String TOKEN = "exact-token";

    @Test
    public void createsA256BitSessionAndAuthenticatesCookieWithoutSlidingExpiry() {
        AtomicLong clock = new AtomicLong(1_000L);
        BrowserSessionStore store = store(clock);

        BrowserSessionStore.Session session = store.createSession(TOKEN);

        assertNotNull(session);
        assertEquals(32, Base64.getUrlDecoder().decode(session.id).length);
        assertEquals(
                1_000L + BrowserSessionStore.SESSION_TTL_MILLISECONDS,
                session.expiresAtMilliseconds
        );
        assertEquals(
                session.id,
                BrowserSessionStore.extractSessionId(
                        "theme=dark; " + BrowserSessionStore.COOKIE_NAME + '=' + session.id
                                + "; other=value"
                )
        );

        clock.set(session.expiresAtMilliseconds - 1L);
        BrowserSessionStore.Session authenticated = store.authenticateCookie(
                BrowserSessionStore.COOKIE_NAME + '=' + session.id
        );
        assertNotNull(authenticated);
        assertEquals(session.expiresAtMilliseconds, authenticated.expiresAtMilliseconds);
    }

    @Test
    public void tokenComparisonIsExact() {
        BrowserSessionStore store = store(new AtomicLong());

        assertNull(store.createSession(null));
        assertNull(store.createSession(""));
        assertNull(store.createSession("Exact-token"));
        assertNull(store.createSession(" exact-token"));
        assertNull(store.createSession("exact-token "));
        assertNull(store.createSession("exact-tokeн"));
        assertNotNull(store.createSession(TOKEN));
    }

    @Test
    public void expiresAtTheFixedTwelveHourBoundary() {
        AtomicLong clock = new AtomicLong(500L);
        BrowserSessionStore store = store(clock);
        BrowserSessionStore.Session session = store.createSession(TOKEN);
        assertNotNull(session);

        clock.set(session.expiresAtMilliseconds);

        assertNull(store.authenticateSessionId(session.id));
        assertNull(store.authenticateCookie(
                BrowserSessionStore.COOKIE_NAME + '=' + session.id
        ));
    }

    @Test
    public void rejectsAmbiguousOrMalformedCookieValues() {
        BrowserSessionStore store = store(new AtomicLong());
        BrowserSessionStore.Session session = store.createSession(TOKEN);
        assertNotNull(session);
        String valid = BrowserSessionStore.COOKIE_NAME + '=' + session.id;

        assertNotNull(store.authenticateCookie(valid));
        assertNull(store.authenticateCookie(null));
        assertNull(store.authenticateCookie(""));
        assertNull(store.authenticateCookie(BrowserSessionStore.COOKIE_NAME));
        assertNull(store.authenticateCookie(BrowserSessionStore.COOKIE_NAME + '='));
        assertNull(store.authenticateCookie(valid + '='));
        assertNull(store.authenticateCookie(
                BrowserSessionStore.COOKIE_NAME + "=\"" + session.id + "\""
        ));
        assertNull(store.authenticateCookie(
                valid + "; " + BrowserSessionStore.COOKIE_NAME + '=' + session.id
        ));
        assertNull(store.authenticateCookie(
                "Poweramp_remote_session=" + session.id
        ));
        assertNull(store.authenticateCookie(
                BrowserSessionStore.COOKIE_NAME + '=' + session.id.substring(1)
        ));
    }

    @Test
    public void boundsSessionsAndEvictsTheOldest() {
        BrowserSessionStore store = store(new AtomicLong());
        BrowserSessionStore.Session first = store.createSession(TOKEN);
        assertNotNull(first);

        BrowserSessionStore.Session newest = first;
        for (int index = 1; index <= BrowserSessionStore.MAX_SESSIONS; index++) {
            newest = store.createSession(TOKEN);
            assertNotNull(newest);
        }

        assertNull(store.authenticateSessionId(first.id));
        assertNotNull(store.authenticateSessionId(newest.id));
    }

    @Test
    public void invalidationClearAndCloseRemoveSessions() {
        BrowserSessionStore store = store(new AtomicLong());
        BrowserSessionStore.Session first = store.createSession(TOKEN);
        BrowserSessionStore.Session second = store.createSession(TOKEN);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.id, second.id);

        assertTrue(store.invalidateCookie(
                BrowserSessionStore.COOKIE_NAME + '=' + first.id
        ));
        assertFalse(store.invalidateCookie(
                BrowserSessionStore.COOKIE_NAME + '=' + first.id
        ));
        assertNull(store.authenticateSessionId(first.id));

        store.clear();
        assertNull(store.authenticateSessionId(second.id));

        BrowserSessionStore.Session third = store.createSession(TOKEN);
        assertNotNull(third);
        store.close();
        assertNull(store.authenticateSessionId(third.id));
        assertNull(store.createSession(TOKEN));
    }

    @Test
    public void cookieHeadersUseHttpOnlyStrictAndApiPathWithoutSecureOnHttp() {
        BrowserSessionStore store = store(new AtomicLong());
        BrowserSessionStore.Session session = store.createSession(TOKEN);
        assertNotNull(session);

        String setCookie = BrowserSessionStore.setCookieHeader(session);
        assertEquals(
                BrowserSessionStore.COOKIE_NAME + '=' + session.id
                        + "; Path=/api/v1/; HttpOnly; SameSite=Strict; Max-Age=43200",
                setCookie
        );
        assertFalse(setCookie.contains("; Secure"));
        assertEquals(
                BrowserSessionStore.COOKIE_NAME
                        + "=; Path=/api/v1/; HttpOnly; SameSite=Strict; Max-Age=0",
                BrowserSessionStore.clearCookieHeader()
        );
    }

    private static BrowserSessionStore store(AtomicLong clock) {
        return new BrowserSessionStore(TOKEN, clock::get, new CountingSecureRandom());
    }

    private static final class CountingSecureRandom extends SecureRandom {
        private int sequence = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = 0;
            }
            bytes[bytes.length - 1] = (byte) sequence++;
        }
    }
}
