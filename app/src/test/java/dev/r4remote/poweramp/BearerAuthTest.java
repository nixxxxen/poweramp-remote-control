package dev.r4remote.poweramp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BearerAuthTest {
    @Test
    public void acceptsMatchingBearerToken() {
        assertTrue(BearerAuth.isAuthorized("Bearer secret-token", "secret-token"));
    }

    @Test
    public void acceptsCaseInsensitiveSchemeAndSurroundingTokenWhitespace() {
        assertTrue(BearerAuth.isAuthorized("bearer   secret-token  ", "secret-token"));
        assertTrue(BearerAuth.isAuthorized("BEARER secret-token", "secret-token"));
    }

    @Test
    public void tokenComparisonRemainsCaseSensitive() {
        assertFalse(BearerAuth.isAuthorized("Bearer Secret-Token", "secret-token"));
    }

    @Test
    public void rejectsMissingMalformedAndEmptyHeaders() {
        assertFalse(BearerAuth.isAuthorized(null, "secret-token"));
        assertFalse(BearerAuth.isAuthorized("", "secret-token"));
        assertFalse(BearerAuth.isAuthorized("Bearer", "secret-token"));
        assertFalse(BearerAuth.isAuthorized("Bearer ", "secret-token"));
        assertFalse(BearerAuth.isAuthorized("Basic secret-token", "secret-token"));
        assertFalse(BearerAuth.isAuthorized("secret-token", "secret-token"));
    }

    @Test
    public void rejectsMissingEmptyAndWrongExpectedTokens() {
        assertFalse(BearerAuth.isAuthorized("Bearer secret-token", null));
        assertFalse(BearerAuth.isAuthorized("Bearer secret-token", ""));
        assertFalse(BearerAuth.isAuthorized("Bearer wrong-token", "secret-token"));
    }

    @Test
    public void supportsNonAsciiTokensWithoutLossyConversion() {
        assertTrue(BearerAuth.isAuthorized("Bearer токен-安全", "токен-安全"));
        assertFalse(BearerAuth.isAuthorized("Bearer токен-安全", "токен-безопасный"));
    }
}
