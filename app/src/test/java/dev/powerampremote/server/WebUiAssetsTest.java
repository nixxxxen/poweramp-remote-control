package dev.powerampremote.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class WebUiAssetsTest {
    @Test
    public void exposesOnlyTheThreeDocumentAssetsWithCorrectTypes() {
        assertEquals(
                "text/html; charset=utf-8",
                asset(WebUiAssets.ROOT_PATH).contentType
        );
        assertEquals(
                "text/css; charset=utf-8",
                asset(WebUiAssets.STYLE_PATH).contentType
        );
        assertEquals(
                "text/javascript; charset=utf-8",
                asset(WebUiAssets.SCRIPT_PATH).contentType
        );
        assertNull(WebUiAssets.forPath("/missing"));
        assertNull(WebUiAssets.forPath("/api/v1/state"));
    }

    @Test
    public void htmlLoadsExternalAssetsAndContainsTheRequestedControls() {
        String html = text(WebUiAssets.ROOT_PATH);

        assertTrue(html.contains("name=\"viewport\""));
        assertTrue(html.contains("href=\"/app.css\""));
        assertTrue(html.contains("src=\"/app.js\""));
        assertTrue(html.contains("id=\"loginForm\""));
        assertTrue(html.contains("id=\"artwork\""));
        assertTrue(html.contains("id=\"album\""));
        assertTrue(html.contains("id=\"audioInfo\""));
        assertTrue(html.contains("id=\"sourceInfo\""));
        assertTrue(html.contains("id=\"seek\""));
        assertTrue(html.contains("id=\"volume\""));
        assertTrue(html.contains("id=\"previous\""));
        assertTrue(html.contains("id=\"playPause\""));
        assertTrue(html.contains("id=\"next\""));
        assertTrue(html.contains("id=\"dislike\""));
        assertTrue(html.contains("id=\"clearRating\""));
        assertTrue(html.contains("id=\"like\""));
        assertTrue(html.contains("id=\"shuffle\""));
        assertTrue(html.contains("aria-pressed=\"false\""));
        assertFalse(html.contains("<style"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    public void scriptUsesCookieSessionRestAndEventDrivenWebSocket() {
        String script = text(WebUiAssets.SCRIPT_PATH);

        assertTrue(script.contains("/api/v1/session"));
        assertTrue(script.contains("/api/v1/state"));
        assertTrue(script.contains("/api/v1/control"));
        assertTrue(script.contains("/api/v1/events"));
        assertTrue(script.contains("credentials: \"same-origin\""));
        assertTrue(script.contains("new WebSocket(eventsUrl())"));
        assertTrue(script.contains("async function handleSocketClose"));
        assertTrue(script.contains("response.status === 401"));
        assertTrue(script.contains("sendControl(\"previous\")"));
        assertTrue(script.contains("sendControl(\"next\")"));
        assertTrue(script.contains("? \"pause\" : \"play\""));
        assertTrue(script.contains("seek.addEventListener(\"change\""));
        assertTrue(script.contains("sendControl(\"seek\", value)"));
        assertTrue(script.contains("state.volumeMax"));
        assertTrue(script.contains("state.volumeControlAvailable"));
        assertTrue(script.contains("sendControl(\"set_volume\", value)"));
        assertTrue(script.contains("state.album"));
        assertTrue(script.contains("state.fileTypeName"));
        assertTrue(script.contains("state.codec"));
        assertTrue(script.contains("state.bitsPerSample"));
        assertTrue(script.contains("state.sampleRate"));
        assertTrue(script.contains("state.bitRate"));
        assertTrue(script.contains("state.sourceCategoryName"));
        assertTrue(script.contains("state.positionInList"));
        assertTrue(script.contains("state.listSize"));
        assertTrue(script.contains("sendControl(\"set_rating\", 0)"));
        assertTrue(script.contains("currentState?.rating === 1 ? 0 : 1"));
        assertTrue(script.contains("currentState?.rating === 5 ? 0 : 5"));
        assertTrue(script.contains("? \"shuffle_off\" : \"shuffle_on\""));

        assertFalse(script.contains("Authorization"));
        assertFalse(script.contains("localStorage"));
        assertFalse(script.contains("sessionStorage"));
        assertFalse(script.contains("setInterval("));
        assertTrue(script.contains("artworkKey = null"));
    }

    @Test
    public void stylesheetHasPhoneAndAccessibilityAdaptation() {
        String css = text(WebUiAssets.STYLE_PATH);

        assertTrue(css.contains("width: min(100%, 28rem)"));
        assertTrue(css.contains("@media (max-width: 23rem)"));
        assertTrue(css.contains("width: min(52vw, 25dvh, 13.5rem)"));
        assertTrue(css.contains("grid-template-columns: 2.75rem"));
        assertTrue(css.contains("overflow-wrap: anywhere"));
        assertTrue(css.contains("env(safe-area-inset-top)"));
        assertTrue(css.contains(":focus-visible"));
        assertTrue(css.contains("prefers-reduced-motion"));
    }

    private static WebUiAssets.Asset asset(String path) {
        WebUiAssets.Asset asset = WebUiAssets.forPath(path);
        assertNotNull(asset);
        return asset;
    }

    private static String text(String path) {
        return new String(asset(path).body, StandardCharsets.UTF_8);
    }
}
