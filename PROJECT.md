# Poweramp Remote

## Project goal

Develop a reliable native Android client for HiBy R4 that reads Poweramp state, controls supported Poweramp functions, and exposes that same state and control surface to trusted devices on the local network.

Current version: `0.5.0`.

## Actual architecture

The repository contains one native, foreground-only Android activity. It owns:

- `PowerampClient`, the existing adapter for Poweramp's public Intent API;
- `PlaybackStateStore`, the thread-safe immutable state shared by the screen and network API;
- `RemoteApiServer`, a small bounded HTTP/WebSocket server implemented with Android/JDK socket APIs;
- `RemoteArtworkCache`, which encodes the already loaded artwork for authenticated delivery;
- `BrowserSessionStore`, a bounded in-memory cookie-session store derived from the existing API token;
- `WebUiAssets`, the dependency-free embedded HTML/CSS/JavaScript remote.

There is no separate phone application, WebView, cloud service, or second foreground service. The Web UI is served by the same local server. The network server starts in `MainActivity.onStart()` and stops in `onStop()`, matching the existing foreground-only lifecycle. A future always-on remote will require moving the same components into a proper foreground service.

The HTTP server does not parse Poweramp broadcasts or send Poweramp broadcasts itself. It only reads `PlaybackStateStore` and submits validated commands through the existing `PowerampClient` command methods on Android's main thread.

No external server framework is used. Connections, request sizes, WebSocket frames, and concurrent WebSocket clients are bounded.

## Local API v1

The fixed port is `8765`. The server listens on the device's local interfaces and the Android UI shows the current IPv4 address, port, server status, WebSocket client count, and token.

External API clients continue to authenticate protected routes with:

```http
Authorization: Bearer <token>
```

The app generates a 256-bit Base64URL token with `SecureRandom`, stores it in private `SharedPreferences`, excludes it from backup/device transfer, shows it locally, and provides a copy button. The token is never accepted in the query string.

The public page at `/` accepts that token only through `POST /api/v1/session`. A successful same-origin login creates a random 256-bit `HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/`, with a fixed 12-hour lifetime. Protected REST, artwork, and WebSocket routes then accept either the existing Bearer header or this cookie. Cookie-authenticated control, logout, and WebSocket requests require an exact same-origin `Origin` header; Bearer clients remain compatible and do not require it. Sessions are in-memory, bounded to 16, and disappear when the app process closes.

### Routes

| Method | Path | Result |
|---|---|---|
| `GET` | `/` | Public embedded Web UI (plus `/app.css` and `/app.js`) |
| `POST` | `/api/v1/session` | Exchange the displayed token for a browser session cookie |
| `DELETE` | `/api/v1/session` | End the current browser session |
| `GET` | `/api/v1/state` | Current full state as JSON |
| `POST` | `/api/v1/control` | Validate and enqueue one command; success is `202 Accepted` |
| `GET` + WebSocket upgrade | `/api/v1/events` | Initial full state, then event-driven full-state updates |
| `GET` | `/api/v1/artwork` | Current JPEG artwork when `state.artwork` is non-null |

There is no CORS API, polling endpoint, credential in a URL, or command channel over WebSocket.

### State JSON

`GET /api/v1/state` and every WebSocket text message use exactly the same flat JSON object:

```json
{
  "apiVersion": 1,
  "revision": 42,
  "powerampAvailable": true,
  "hasTrack": true,
  "title": "Track",
  "artist": "Artist",
  "album": "Album",
  "artwork": "/api/v1/artwork",
  "fileType": 1,
  "fileTypeName": "FLAC",
  "codec": "flac",
  "bitsPerSample": 24,
  "sampleRate": 96000,
  "bitRate": 1411200,
  "sourceCategory": 800,
  "sourceCategoryName": "Очередь",
  "sourceCategoryUri": "content://com.maxmpz.audioplayer/queue",
  "positionInList": 0,
  "listSize": 10,
  "durationSeconds": 180,
  "positionSeconds": 37,
  "playbackState": "playing",
  "rating": 5,
  "liked": true,
  "disliked": false,
  "shuffle": true,
  "shuffleMode": 2
}
```

Rules:

- unavailable optional values are JSON `null`; no guessed metadata is generated;
- `playbackState` is `playing`, `paused`, `stopped`, or `null`;
- `rating` is `0…5` or `null`; rating `5` derives `liked=true`, rating `1` derives `disliked=true`;
- `shuffle` is the binary view while `shuffleMode` preserves Poweramp's raw mode;
- `bitRate` is the raw Poweramp value, with no unit conversion;
- `positionInList` is the raw Poweramp value, with no `+1` or `-1` normalization;
- `artwork` is an authenticated relative path or `null`; responses use `Cache-Control: no-store`.

### Control JSON

Accepted request bodies are:

```json
{"action":"play"}
{"action":"pause"}
{"action":"previous"}
{"action":"next"}
{"action":"seek","value":37}
{"action":"shuffle_on"}
{"action":"shuffle_off"}
{"action":"set_rating","value":4}
```

`seek` accepts an absolute integer position in seconds (`0…2147483647`) and uses Poweramp `Commands.SEEK` with the public `pos` extra. A successful send triggers one delayed `POS_SYNC` request; it does not optimistically change confirmed state. `set_rating` accepts only integer values `0…5`. Like is exact rating `5`, Dislike is exact rating `1`, and clearing either is rating `0`, consistent with version `0.3.0`.

`202 Accepted` means that the validated command was handed to the active Android client; it does not claim that Poweramp already applied it. The confirmed result arrives through the next Poweramp event/state update.

### WebSocket format

Connect to `ws://<R4-IP>:8765/api/v1/events` with the same Bearer header, or let the embedded page connect with its session cookie. Immediately after upgrade, the server sends one complete state object. Subsequent text messages are complete state objects in the identical REST schema, without a wrapper or delta.

Updates are driven by the existing Poweramp events, principally:

- `TRACK_CHANGED`;
- `STATUS_CHANGED`;
- `PLAYING_MODE_CHANGED`.

Artwork readiness and explicit position/rating state changes may also produce a newer revision. The server does not poll Poweramp and does not push one message per elapsed second. Revisions sent to one connection are monotonic; intermediate updates may be coalesced for a slow client.

### Embedded Web UI

Open `http://<R4-IP>:8765/` on a phone in the same LAN and enter the token shown on the R4. The page then displays artwork, title, artist, elapsed position/duration, Previous, current-state Play/Pause, Next, and a seekbar. Releasing the seekbar sends exactly one `seek` command. The page performs one initial state request, receives subsequent state through WebSocket, and advances the displayed position locally between events; it does not poll the state endpoint. Artwork is loaded from `/api/v1/artwork` with the session cookie.

If WebSocket disconnects, the page performs one authenticated state probe. A `401` returns it to the token form; a network outage uses bounded exponential reconnect backoff. Session expiry, logout, and session eviction close associated WebSockets so they cannot retain one of the four client slots.

## Implemented Poweramp capabilities

- title, artist, album, artwork;
- codec/file type, bit depth, sample rate, bitrate;
- source category, category URI, raw list position, list size;
- playback state, position, duration;
- play, pause, previous, next, absolute seek;
- rating `0…5`, Like, Dislike;
- binary shuffle with preserved raw mode;
- automatic event-driven updates;
- embedded phone-sized Web UI using the existing REST/artwork/WebSocket endpoints.

Lyrics remain intentionally out of scope because the public Intent API exposes `lyricsState`, not the lyrics text.

## Security model

Version `0.5.0` is a trusted-LAN prototype. Authentication prevents casual unauthorised commands, but HTTP and `ws://` are not encrypted, so the initial token login, cookie, and metadata can be observed on an untrusted network. The Web UI uses no browser storage for the token, serves external script/style assets under a restrictive CSP, and uses one state auth probe only after a WebSocket disconnect; normal state updates remain event-driven. Do not expose port `8765` to the internet. TLS/pairing remains future work.

## Known semantic uncertainties

The exact unit of Poweramp `bitRate` and index base of `posInList` still require verification on HiBy R4. The network API deliberately preserves both raw values until that test is completed.

## Verification baseline

Version `0.5.0` keeps the version `0.4.0` regression suite passing and has been verified with:

- `56/56` JVM tests across 12 suites, including loopback REST/WebSocket/session/Web UI coverage;
- Android lint: `0` errors (`1` non-blocking Gradle wrapper update warning);
- clean debug APK build;
- APK manifest/version inspection and APK Signature Scheme v2 verification;
- packaged source/archive enumeration and SHA-256 hashes.

`STATUS.md` records the latest completed run and remaining device checks.
