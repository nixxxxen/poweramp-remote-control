package dev.powerampremote.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class RemoteApiServerTest {
    private static final String TOKEN =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String SERVER_ID = "AAECAwQFBgcICQoLDA0ODw";

    private PlaybackStateStore stateStore;
    private RemoteArtworkCache artworkCache;
    private RemoteApiServer server;
    private AtomicReference<RemoteCommand> submittedCommand;
    private AtomicLong clock;
    private PairingSecretStore pairingSecrets;
    private TestLibraryProvider libraryProvider;
    private PowerampLibrarySource librarySource;
    private AtomicReference<LibraryItem.PlayTarget> submittedLibraryTarget;
    private int port;

    @Before
    public void setUp() throws Exception {
        port = unusedLoopbackPort();
        stateStore = new PlaybackStateStore(0L);
        stateStore.setTrack(new TrackInfo(
                1L,
                2L,
                "Network Track",
                "Album",
                "Artist",
                180,
                7,
                3,
                new TrackInfo.AudioProperties(
                        PowerampContract.FileTypes.FLAC,
                        "flac",
                        96_000,
                        24,
                        1_411_200
                ),
                new TrackInfo.PlaybackSource(
                        PowerampContract.Categories.QUEUE,
                        null,
                        0,
                        10
                )
        ), 0L);
        stateStore.setPlaybackState(PowerampContract.STATE_PAUSED, 7, 0L);
        artworkCache = new RemoteArtworkCache(stateStore);
        submittedCommand = new AtomicReference<>();
        clock = new AtomicLong(5_000L);
        pairingSecrets = new PairingSecretStore(
                SERVER_ID,
                "Test Player",
                new SecureRandom(),
                clock::get
        );
        libraryProvider = new TestLibraryProvider();
        libraryProvider.rows.put("tracks", Collections.singletonList(row(
                "track_id", 2L,
                "title", "Library Track",
                "artist", "Library Artist",
                "duration_ms", 180_000L
        )));
        libraryProvider.rows.put("queue", Collections.singletonList(row(
                "track_id", 2L,
                "entry_id", 1L,
                "title", "Network Track"
        )));
        libraryProvider.rows.put("search", Collections.singletonList(row(
                "track_id", 2L,
                "title", "Search Result"
        )));
        libraryProvider.rows.put("categorized_search_exact_tracks", Collections.singletonList(row(
                "track_id", 2L,
                "title", "Obsidian",
                "artist", "Northlane",
                "album", "Obsidian"
        )));
        libraryProvider.rows.put("categorized_search_tracks", List.of(
                row("track_id", 2L, "title", "Obsidian", "artist", "Northlane"),
                row("track_id", 3L, "title", "Obsidian Live", "artist", "Another")
        ));
        libraryProvider.rows.put("categorized_search_artists", Collections.emptyList());
        libraryProvider.rows.put("categorized_search_related_artists", Collections.singletonList(
                row("item_id", 8L, "title", "Northlane", "track_count", 1L,
                        "artist_is_unsplit", 0L)
        ));
        libraryProvider.rows.put("categorized_search_albums", Collections.singletonList(
                row("item_id", 9L, "title", "Obsidian", "track_count", 1L)
        ));
        libraryProvider.rows.put("categorized_search_related_albums", List.of(
                row("item_id", 9L, "title", "Obsidian", "track_count", 1L),
                row("item_id", 10L, "title", "Node", "track_count", 11L)
        ));
        libraryProvider.rows.put("artist_member_tracks", List.of(
                row("track_id", 2L, "title", "Obsidian"),
                row("track_id", 4L, "title", "Collaboration")
        ));
        libraryProvider.rows.put("play_queue_entry", Collections.singletonList(row(
                "track_id", 2L,
                "entry_id", 1L,
                "title", "Network Track"
        )));
        librarySource = new PowerampLibrarySource(
                libraryProvider,
                state -> { },
                clock::get
        );
        submittedLibraryTarget = new AtomicReference<>();
        startServer(pairingSecrets);
    }

    private void startServer(PairingSecretStore secretStore) throws Exception {
        if (server != null) server.close();
        port = unusedLoopbackPort();
        CountDownLatch running = new CountDownLatch(1);
        server = new RemoteApiServer(
                port,
                TOKEN,
                stateStore,
                artworkCache,
                command -> {
                    submittedCommand.set(command);
                    return true;
                },
                status -> {
                    if (status.running) {
                        running.countDown();
                    }
                },
                clock::get,
                secretStore,
                SERVER_ID,
                "Test Player",
                librarySource,
                target -> {
                    submittedLibraryTarget.set(target);
                    return true;
                },
                trackId -> null
        );
        server.start();
        assertTrue("Server did not start", running.await(3, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
        if (artworkCache != null) {
            artworkCache.close();
        }
        if (librarySource != null) {
            librarySource.close();
        }
    }

    @Test
    public void restRoutesRequireBearerAndDispatchValidatedControl() throws Exception {
        String unauthorized = http("GET", RemoteApiServer.STATE_PATH, null, null, null);
        assertStatus(unauthorized, 401);

        String state = http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null);
        assertStatus(state, 200);
        assertTrue(state.contains("\"title\":\"Network Track\""));
        assertTrue(state.contains("\"bitRate\":1411200"));
        assertTrue(state.contains("\"positionInList\":0"));

        String accepted = http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                TOKEN,
                "application/json",
                "{\"action\":\"play\"}"
        );
        assertStatus(accepted, 202);
        assertNotNull(submittedCommand.get());
        assertEquals(RemoteCommand.Action.PLAY, submittedCommand.get().action);

        String invalid = http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                TOKEN,
                "application/json",
                "{\"action\":\"set_rating\",\"value\":6}"
        );
        assertStatus(invalid, 400);

        assertStatus(http("GET", RemoteApiServer.ARTWORK_PATH, null, null, null), 401);
        assertStatus(http("GET", RemoteApiServer.ARTWORK_PATH, TOKEN, null, null), 404);
        assertStatus(http("GET", "/missing", TOKEN, null, null), 404);
    }

    @Test
    public void additiveLibrarySearchQueueRoutesRequireBearerAndKeepLegacyRoutesStable()
            throws Exception {
        String[] newGetRoutes = {
                RemoteApiServer.LIBRARY_PATH,
                RemoteApiServer.LIBRARY_TRACKS_PATH,
                RemoteApiServer.SEARCH_PATH + "?q=track",
                RemoteApiServer.CATEGORIZED_SEARCH_PATH + "?q=track",
                RemoteApiServer.QUEUE_PATH
        };
        for (String path : newGetRoutes) {
            assertStatus(http("GET", path, null, null, null), 401);
        }
        assertStatus(http(
                "POST",
                RemoteApiServer.LIBRARY_PLAY_PATH,
                null,
                "application/json",
                "{\"type\":\"queue_entry\",\"entryId\":1}"
        ), 401);

        String capabilities = http(
                "GET",
                RemoteApiServer.LIBRARY_PATH,
                TOKEN,
                null,
                null
        );
        assertStatus(capabilities, 200);
        assertTrue(capabilities.contains("\"status\":\"available\""));
        assertTrue(capabilities.contains("\"read\":true"));
        assertTrue(capabilities.contains("\"playExisting\":true"));
        assertTrue(capabilities.contains("\"add\":false"));
        assertTrue(capabilities.contains("\"remove\":false"));
        assertTrue(capabilities.contains("\"reorder\":false"));
        assertTrue(capabilities.contains("\"playNext\":false"));

        String tracks = http(
                "GET",
                RemoteApiServer.LIBRARY_TRACKS_PATH + "?limit=1",
                TOKEN,
                null,
                null
        );
        assertStatus(tracks, 200);
        assertTrue(tracks.contains("\"title\":\"Library Track\""));
        assertTrue(tracks.contains("\"artwork\":\"/api/v1/library/artwork/tracks/2\""));

        String search = http(
                "GET",
                RemoteApiServer.SEARCH_PATH + "?q=private%20text&limit=3",
                TOKEN,
                null,
                null
        );
        assertStatus(search, 200);
        assertTrue(search.contains("\"title\":\"Search Result\""));

        String categorized = http(
                "GET",
                RemoteApiServer.CATEGORIZED_SEARCH_PATH + "?q=Obsidian&limit=25",
                TOKEN,
                null,
                null
        );
        assertStatus(categorized, 200);
        JSONObject categorizedJson = new JSONObject(body(categorized));
        assertEquals("exact", categorizedJson.getString("trackMatch"));
        JSONArray sections = categorizedJson.getJSONArray("sections");
        assertEquals("tracks", sections.getJSONObject(0).getString("type"));
        assertEquals(1, sections.getJSONObject(0).getJSONArray("items").length());
        assertEquals("artists", sections.getJSONObject(1).getString("type"));
        JSONObject artist = sections.getJSONObject(1).getJSONArray("items").getJSONObject(0);
        assertEquals(8L, artist.getLong("id"));
        assertEquals("artist_membership", artist.getJSONObject("browse").getString("type"));
        assertEquals("albums", sections.getJSONObject(2).getString("type"));
        assertEquals(2, sections.getJSONObject(2).getJSONArray("items").length());

        String memberships = http(
                "GET",
                RemoteApiServer.LIBRARY_ARTISTS_PATH + "/8/member-tracks?limit=25",
                TOKEN,
                null,
                null
        );
        assertStatus(memberships, 200);
        assertEquals(2, new JSONObject(body(memberships)).getJSONArray("items").length());

        String queue = http("GET", RemoteApiServer.QUEUE_PATH, TOKEN, null, null);
        assertStatus(queue, 200);
        assertTrue(queue.contains("\"entryId\":1"));
        assertTrue(queue.contains("\"current\":true"));

        String accepted = http(
                "POST",
                RemoteApiServer.LIBRARY_PLAY_PATH,
                TOKEN,
                "application/json",
                "{\"type\":\"queue_entry\",\"entryId\":1}"
        );
        assertStatus(accepted, 202);
        assertNotNull(submittedLibraryTarget.get());
        assertEquals(LibraryItem.PlayTarget.Type.QUEUE_ENTRY,
                submittedLibraryTarget.get().type);
        assertEquals(1L, submittedLibraryTarget.get().id);

        assertStatus(http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null), 200);
        assertStatus(http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                TOKEN,
                "application/json",
                "{\"action\":\"pause\"}"
        ), 202);
        PairingOffer offer = pairingSecrets.issue();
        assertStatus(http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                        + "\",\"secret\":\"" + offer.secret + "\"}"
        ), 200);
    }

    @Test
    public void libraryRoutesRejectMalformedInputsUrisAndBrowserSessionCookie() throws Exception {
        String[] invalidGetRoutes = {
                RemoteApiServer.LIBRARY_TRACKS_PATH + "?limit=0",
                RemoteApiServer.LIBRARY_TRACKS_PATH + "?offset=1",
                RemoteApiServer.LIBRARY_TRACKS_PATH + "?pageToken=bad",
                RemoteApiServer.SEARCH_PATH,
                RemoteApiServer.SEARCH_PATH + "?q=%GG",
                RemoteApiServer.LIBRARY_ALBUMS_PATH + "/0/tracks",
                RemoteApiServer.LIBRARY_FOLDER_TREE_PATH + "/-1/folders",
                RemoteApiServer.LIBRARY_ARTWORK_PATH + "/file:///private/music"
        };
        for (String path : invalidGetRoutes) {
            assertStatus(http("GET", path, TOKEN, null, null), 400);
        }

        String[] invalidPlayBodies = {
                "{\"uri\":\"file:///private/music.flac\"}",
                "{\"type\":\"track\",\"id\":1,\"uri\":\"https://example.test/x\"}",
                "{\"type\":\"queue_entry\",\"entryId\":0}",
                "{\"type\":\"queue_entry\",\"entryId\":2}"
        };
        for (int index = 0; index < invalidPlayBodies.length; index++) {
            String response = http(
                    "POST",
                    RemoteApiServer.LIBRARY_PLAY_PATH,
                    TOKEN,
                    "application/json",
                    invalidPlayBodies[index]
            );
            assertStatus(response, index == invalidPlayBodies.length - 1 ? 404 : 400);
        }

        String cookie = loginCookie();
        assertStatus(http(
                "GET",
                RemoteApiServer.LIBRARY_TRACKS_PATH,
                null,
                null,
                null,
                header("Cookie", cookie)
        ), 401);
    }

    @Test
    public void libraryProviderFailuresHaveControlledApiStates() throws Exception {
        libraryProvider.failure = PowerampLibraryProvider.Failure.PERMISSION_REQUIRED;
        String denied = http(
                "GET",
                RemoteApiServer.LIBRARY_TRACKS_PATH,
                TOKEN,
                null,
                null
        );
        assertStatus(denied, 403);
        assertTrue(denied.contains("poweramp_data_permission_required"));

        String permissionState = http(
                "GET",
                RemoteApiServer.LIBRARY_PATH,
                TOKEN,
                null,
                null
        );
        assertStatus(permissionState, 200);
        assertTrue(permissionState.contains("\"status\":\"permission_required\""));

        libraryProvider.failure = PowerampLibraryProvider.Failure.UNAVAILABLE;
        String unavailable = http(
                "GET",
                RemoteApiServer.SEARCH_PATH + "?q=private%20query",
                TOKEN,
                null,
                null
        );
        assertStatus(unavailable, 503);
        assertTrue(unavailable.contains("poweramp_provider_unavailable"));

        libraryProvider.failure = null;
        libraryProvider.installed = false;
        String missing = http(
                "GET",
                RemoteApiServer.LIBRARY_TRACKS_PATH,
                TOKEN,
                null,
                null
        );
        assertStatus(missing, 503);
        assertTrue(missing.contains("poweramp_unavailable"));
    }

    @Test
    public void unexpectedLibraryFailureIsSanitizedAndLegacyApiKeepsServing() throws Exception {
        libraryProvider.unexpectedFailure = true;
        String failure = http(
                "GET",
                RemoteApiServer.SEARCH_PATH + "?q=private%20search",
                TOKEN,
                null,
                null
        );
        assertStatus(failure, 500);
        assertTrue(failure.contains("library_internal_error"));
        assertFalse(failure.contains("private search"));

        libraryProvider.unexpectedFailure = false;
        assertStatus(http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null), 200);
    }

    @Test
    public void oneTimePairingRouteExchangesQrSecretWithoutBearer() throws Exception {
        PairingOffer offer = pairingSecrets.issue();
        String[] invalidRequests = {
                "{not-json}",
                "{\"serverId\":\"" + SERVER_ID + "\",\"secret\":\""
                        + offer.secret + "\"}",
                "{\"apiVersion\":1,\"secret\":\"" + offer.secret + "\"}",
                "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID + "\"}",
                "{\"apiVersion\":\"1\",\"serverId\":\"" + SERVER_ID
                        + "\",\"secret\":\"" + offer.secret + "\"}",
                "{\"apiVersion\":1.0,\"serverId\":\"" + SERVER_ID
                        + "\",\"secret\":\"" + offer.secret + "\"}",
                "{\"apiVersion\":2,\"serverId\":\"" + SERVER_ID
                        + "\",\"secret\":\"" + offer.secret + "\"}",
                "{\"apiVersion\":1,\"serverId\":7,\"secret\":\""
                        + offer.secret + "\"}",
                "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                        + "\",\"secret\":false}"
        };
        for (String invalidRequest : invalidRequests) {
            assertStatus(http(
                    "POST",
                    RemoteApiServer.PAIRING_PATH,
                    null,
                    "application/json",
                    invalidRequest
            ), 400);
            assertTrue(pairingSecrets.isActive(offer));
        }

        String request = " { \"secret\" : \"" + offer.secret
                + "\", \"diagnostic\" : {\"ignored\":true}, \"serverId\" : \""
                + SERVER_ID + "\", \"apiVersion\" : 1 } ";

        String paired = http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json; charset=utf-8",
                request
        );
        assertStatus(paired, 200);
        assertTrue(paired.contains("\"serverId\":\"" + SERVER_ID + "\""));
        assertTrue(paired.contains("\"deviceName\":\"Test Player\""));
        assertTrue(paired.contains("\"token\":\"" + TOKEN + "\""));

        String reused = http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                request
        );
        assertStatus(reused, 401);

        PairingOffer expiredOffer = pairingSecrets.issue();
        clock.set(expiredOffer.expiresAtMilliseconds);
        String expiredRequest = "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + expiredOffer.secret + "\"}";
        assertStatus(http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                expiredRequest
        ), 401);

        PairingOffer wrongSecretOffer = pairingSecrets.issue();
        String wrongSecretRequest = "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + TOKEN + "\"}";
        assertStatus(http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                wrongSecretRequest
        ), 401);
        assertTrue(pairingSecrets.isActive(wrongSecretOffer));

        // Every rejection is request-local: the listener and authenticated API stay alive.
        assertStatus(http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null), 200);
        assertStatus(http("GET", RemoteApiServer.PAIRING_PATH, null, null, null), 405);
    }

    @Test
    public void unexpectedPairingExceptionIsLoggedAndContainedAtRequestBoundary()
            throws Exception {
        AtomicBoolean failConsume = new AtomicBoolean();
        PairingSecretStore failingStore = new PairingSecretStore(
                SERVER_ID,
                "Test Player",
                new SecureRandom(),
                () -> {
                    if (failConsume.get()) throw new IllegalStateException("test consume failure");
                    return clock.get();
                }
        );
        PairingOffer offer = failingStore.issue();
        startServer(failingStore);
        failConsume.set(true);

        String request = "{\"apiVersion\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"secret\":\"" + offer.secret + "\"}";
        assertStatus(http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                request
        ), 500);

        // The internal failure occurred before acceptance and must not invalidate the offer.
        failConsume.set(false);
        assertStatus(http(
                "POST",
                RemoteApiServer.PAIRING_PATH,
                null,
                "application/json",
                request
        ), 200);

        assertStatus(http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null), 200);
    }

    @Test
    public void websocketRequiresBearerAndPushesInitialAndChangedFullState() throws Exception {
        String restState = body(http("GET", RemoteApiServer.STATE_PATH, TOKEN, null, null));
        try (Socket unauthorized = connect()) {
            writeWebSocketUpgrade(unauthorized, null);
            String response = readHttpResponse(unauthorized.getInputStream());
            assertStatus(response, 401);
        }

        try (Socket socket = connect()) {
            writeWebSocketUpgrade(socket, TOKEN);
            String headers = readHeaders(socket.getInputStream());
            assertStatus(headers, 101);

            String initial = readTextFrame(socket.getInputStream());
            assertEquals(restState, initial);
            assertTrue(initial.contains("\"apiVersion\":1"));
            assertTrue(initial.contains("\"title\":\"Network Track\""));
            long initialRevision = revision(initial);

            stateStore.setShuffleMode(PowerampContract.ShuffleModes.SONGS, 6_000L);

            String changed = readTextFrame(socket.getInputStream());
            assertTrue(changed.contains("\"title\":\"Network Track\""));
            assertTrue(changed.contains("\"shuffle\":true"));
            assertTrue(revision(changed) > initialRevision);
        }
    }

    @Test
    public void servesPublicWebUiWithoutExposingStateOrToken() throws Exception {
        String page = http("GET", WebUiAssets.ROOT_PATH, null, null, null);
        assertStatus(page, 200);
        assertTrue(page.contains("Content-Type: text/html; charset=utf-8"));
        assertTrue(page.contains("Content-Security-Policy: default-src 'none'"));
        assertTrue(page.contains("X-Frame-Options: DENY"));
        assertTrue(page.contains("Referrer-Policy: no-referrer"));
        assertTrue(page.contains("id=\"loginForm\""));
        assertTrue(!page.contains("\"title\":\"Network Track\""));
        assertTrue(!page.contains(TOKEN));

        String style = http("GET", WebUiAssets.STYLE_PATH, null, null, null);
        assertStatus(style, 200);
        assertTrue(style.contains("Content-Type: text/css; charset=utf-8"));

        String script = http("GET", WebUiAssets.SCRIPT_PATH, null, null, null);
        assertStatus(script, 200);
        assertTrue(script.contains("Content-Type: text/javascript; charset=utf-8"));
        assertTrue(script.contains("new WebSocket(eventsUrl())"));

        assertStatus(
                http("POST", WebUiAssets.ROOT_PATH, null, "application/json", "{}"),
                405
        );
    }

    @Test
    public void browserSessionAuthenticatesRestArtworkControlAndLogout() throws Exception {
        Map<String, String> originHeader = header("Origin", origin());

        assertStatus(http(
                "POST",
                RemoteApiServer.SESSION_PATH,
                null,
                "application/json",
                "{\"token\":\"" + TOKEN + "\"}"
        ), 403);
        assertStatus(http(
                "POST",
                RemoteApiServer.SESSION_PATH,
                null,
                "application/json",
                "{\"token\":\"" + TOKEN + "\"}",
                header("Origin", "http://example.test")
        ), 403);

        String rejected = http(
                "POST",
                RemoteApiServer.SESSION_PATH,
                null,
                "application/json",
                "{\"token\":\"wrong-token\"}",
                originHeader
        );
        assertStatus(rejected, 401);
        assertTrue(!rejected.contains("Set-Cookie:"));

        String login = http(
                "POST",
                RemoteApiServer.SESSION_PATH,
                null,
                "application/json; charset=utf-8",
                "{ \"token\" : \"" + TOKEN + "\" }",
                originHeader
        );
        assertStatus(login, 200);
        String setCookie = headerValue(login, "Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Max-Age=43200"));
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        String state = http(
                "GET",
                RemoteApiServer.STATE_PATH,
                null,
                null,
                null,
                header("Cookie", cookie)
        );
        assertStatus(state, 200);
        assertTrue(state.contains("\"title\":\"Network Track\""));
        assertStatus(http(
                "GET",
                RemoteApiServer.ARTWORK_PATH,
                null,
                null,
                null,
                header("Cookie", cookie)
        ), 404);

        submittedCommand.set(null);
        assertStatus(http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                null,
                "application/json",
                "{\"action\":\"seek\",\"value\":37}",
                header("Cookie", cookie)
        ), 403);
        assertStatus(http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                null,
                "application/json",
                "{\"action\":\"seek\",\"value\":37}",
                headers("Cookie", cookie, "Origin", "http://example.test")
        ), 403);
        assertEquals(null, submittedCommand.get());

        Map<String, String> cookieAndOrigin = headers(
                "Cookie", cookie,
                "Origin", origin()
        );
        String accepted = http(
                "POST",
                RemoteApiServer.CONTROL_PATH,
                null,
                "application/json",
                "{\"action\":\"seek\",\"value\":37}",
                cookieAndOrigin
        );
        assertStatus(accepted, 202);
        assertNotNull(submittedCommand.get());
        assertEquals(RemoteCommand.Action.SEEK, submittedCommand.get().action);
        assertEquals(37, submittedCommand.get().positionSeconds);

        Map<String, String> invalidBearerAndCookie = headers(
                "Authorization", "Bearer wrong-token",
                "Cookie", cookie
        );
        assertStatus(http(
                "GET",
                RemoteApiServer.STATE_PATH,
                null,
                null,
                null,
                invalidBearerAndCookie
        ), 401);
        assertStatus(http(
                "GET",
                RemoteApiServer.STATE_PATH,
                TOKEN,
                null,
                null,
                header("Cookie", BrowserSessionStore.COOKIE_NAME + "=malformed")
        ), 200);

        String logout = http(
                "DELETE",
                RemoteApiServer.SESSION_PATH,
                null,
                null,
                null,
                cookieAndOrigin
        );
        assertStatus(logout, 200);
        assertTrue(logout.contains("Max-Age=0"));
        assertStatus(http(
                "GET",
                RemoteApiServer.STATE_PATH,
                null,
                null,
                null,
                header("Cookie", cookie)
        ), 401);
    }

    @Test
    public void browserSessionWebSocketRequiresOriginAndExpiresBeforeNextPush()
            throws Exception {
        String cookie = loginCookie();

        try (Socket missingOrigin = connect()) {
            writeWebSocketUpgrade(missingOrigin, null, cookie, null);
            assertStatus(readHttpResponse(missingOrigin.getInputStream()), 403);
        }
        try (Socket wrongOrigin = connect()) {
            writeWebSocketUpgrade(wrongOrigin, null, cookie, "http://example.test");
            assertStatus(readHttpResponse(wrongOrigin.getInputStream()), 403);
        }

        try (Socket socket = connect()) {
            writeWebSocketUpgrade(socket, null, cookie, origin());
            assertStatus(readHeaders(socket.getInputStream()), 101);
            assertTrue(readTextFrame(socket.getInputStream()).contains("Network Track"));

            clock.addAndGet(BrowserSessionStore.SESSION_TTL_MILLISECONDS);
            stateStore.setShuffleMode(PowerampContract.ShuffleModes.SONGS, clock.get());

            assertEquals(-1, socket.getInputStream().read());
        }

        assertStatus(http(
                "GET",
                RemoteApiServer.STATE_PATH,
                null,
                null,
                null,
                header("Cookie", cookie)
        ), 401);
    }

    @Test
    public void logoutAndEvictionCloseActiveSessionWebSockets() throws Exception {
        String logoutCookie = loginCookie();
        try (Socket socket = connect()) {
            writeWebSocketUpgrade(socket, null, logoutCookie, origin());
            assertStatus(readHeaders(socket.getInputStream()), 101);
            readTextFrame(socket.getInputStream());

            assertStatus(http(
                    "DELETE",
                    RemoteApiServer.SESSION_PATH,
                    null,
                    null,
                    null,
                    headers("Cookie", logoutCookie, "Origin", origin())
            ), 200);
            assertEquals(-1, socket.getInputStream().read());
        }

        String evictedCookie = loginCookie();
        try (Socket socket = connect()) {
            writeWebSocketUpgrade(socket, null, evictedCookie, origin());
            assertStatus(readHeaders(socket.getInputStream()), 101);
            readTextFrame(socket.getInputStream());

            for (int index = 0; index < BrowserSessionStore.MAX_SESSIONS; index++) {
                loginCookie();
            }
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    private String http(
            String method,
            String path,
            String token,
            String contentType,
            String body
    ) throws IOException {
        return http(method, path, token, contentType, body, Collections.emptyMap());
    }

    private String http(
            String method,
            String path,
            String token,
            String contentType,
            String body,
            Map<String, String> extraHeaders
    ) throws IOException {
        try (Socket socket = connect()) {
            byte[] bodyBytes = body == null
                    ? new byte[0]
                    : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n");
            if (token != null) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            if (contentType != null) {
                request.append("Content-Type: ").append(contentType).append("\r\n");
            }
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                request.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            if ("POST".equals(method)) {
                request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            output.write(bodyBytes);
            output.flush();
            return readHttpResponse(socket.getInputStream());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private void writeWebSocketUpgrade(Socket socket, String token) throws IOException {
        writeWebSocketUpgrade(socket, token, null, null);
    }

    private void writeWebSocketUpgrade(
            Socket socket,
            String token,
            String cookie,
            String origin
    ) throws IOException {
        StringBuilder request = new StringBuilder()
                .append("GET ").append(RemoteApiServer.EVENTS_PATH).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Sec-WebSocket-Key: AAECAwQFBgcICQoLDA0ODw==\r\n");
        if (token != null) {
            request.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        if (cookie != null) {
            request.append("Cookie: ").append(cookie).append("\r\n");
        }
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(
                request.toString().getBytes(StandardCharsets.ISO_8859_1)
        );
        socket.getOutputStream().flush();
    }

    private String loginCookie() throws IOException {
        String response = http(
                "POST",
                RemoteApiServer.SESSION_PATH,
                null,
                "application/json",
                "{\"token\":\"" + TOKEN + "\"}",
                header("Origin", origin())
        );
        assertStatus(response, 200);
        String setCookie = headerValue(response, "Set-Cookie");
        assertNotNull(setCookie);
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private String origin() {
        return "http://127.0.0.1:" + port;
    }

    private static Map<String, String> header(String name, String value) {
        return Collections.singletonMap(name, value);
    }

    private static Map<String, String> headers(
            String firstName,
            String firstValue,
            String secondName,
            String secondValue
    ) {
        Map<String, String> values = new HashMap<>();
        values.put(firstName, firstValue);
        values.put(secondName, secondValue);
        return values;
    }

    private static String readHttpResponse(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[1_024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            response.write(buffer, 0, read);
        }
        return response.toString(StandardCharsets.UTF_8.name());
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("incomplete HTTP headers");
            }
            bytes.write(value);
            if ((matched == 0 || matched == 2) && value == '\r'
                    || (matched == 1 || matched == 3) && value == '\n') {
                matched++;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String readTextFrame(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0 || (first & 0x0F) != 0x01 || (second & 0x80) != 0) {
            throw new IOException("expected an unmasked server text frame");
        }
        long length = second & 0x7F;
        if (length == 126L) {
            length = ((input.read() & 0xFFL) << 8) | (input.read() & 0xFFL);
        } else if (length == 127L) {
            length = 0L;
            for (int index = 0; index < 8; index++) {
                length = (length << 8) | (input.read() & 0xFFL);
            }
        }
        if (length < 0L || length > 64 * 1024L) {
            throw new IOException("unexpected frame length");
        }
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int read = input.read(payload, offset, payload.length - offset);
            if (read < 0) {
                throw new IOException("incomplete websocket payload");
            }
            offset += read;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static long revision(String json) {
        String marker = "\"revision\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("revision missing from " + json);
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String body(String response) {
        int separator = response.indexOf("\r\n\r\n");
        if (separator < 0) {
            throw new AssertionError("HTTP body separator missing from " + response);
        }
        return response.substring(separator + 4);
    }

    private static String headerValue(String response, String name) {
        String prefix = name + ":";
        int headerEnd = response.indexOf("\r\n\r\n");
        String headerBlock = headerEnd >= 0 ? response.substring(0, headerEnd) : response;
        for (String line : headerBlock.split("\r\n")) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static void assertStatus(String response, int status) {
        assertTrue(
                "Expected HTTP " + status + ", response was: " + response,
                response.startsWith("HTTP/1.1 " + status + " ")
        );
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static final class TestLibraryProvider implements PowerampLibraryProvider {
        final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        volatile boolean installed = true;
        volatile Failure failure;
        volatile boolean unexpectedFailure;

        @Override
        public boolean isPowerampInstalled() {
            return installed;
        }

        @Override
        public Rows query(
                PowerampLibraryContract.Query query,
                int limit,
                LibraryCancellation cancellation
        ) throws ProviderException {
            if (unexpectedFailure) {
                throw new IllegalStateException("private search/provider URI");
            }
            if (failure != null) {
                throw new ProviderException(failure);
            }
            List<Map<String, Object>> available = rows.getOrDefault(
                    query.category,
                    Collections.emptyList()
            );
            return new TestRows(
                    available.subList(0, Math.min(limit, available.size())),
                    cancellation
            );
        }
    }

    private static final class TestRows implements PowerampLibraryProvider.Rows {
        private final List<Map<String, Object>> rows;
        private final LibraryCancellation cancellation;
        private int index = -1;

        TestRows(List<Map<String, Object>> rows, LibraryCancellation cancellation) {
            this.rows = rows;
            this.cancellation = cancellation;
        }

        @Override
        public boolean moveToNext() throws PowerampLibraryProvider.ProviderException {
            if (cancellation.isCancelled()) {
                throw new PowerampLibraryProvider.ProviderException(
                        PowerampLibraryProvider.Failure.CANCELLED
                );
            }
            index++;
            return index < rows.size();
        }

        @Override
        public Long longValue(String column) {
            Object value = rows.get(index).get(column);
            return value instanceof Number ? ((Number) value).longValue() : null;
        }

        @Override
        public String textValue(String column) {
            Object value = rows.get(index).get(column);
            return value instanceof String ? (String) value : null;
        }

        @Override
        public void close() {
            // Nothing to release in the in-memory fake.
        }
    }
}
