# Poweramp Remote

## Project goal

Develop a reliable native Android server for HiBy R4 that integrates with Poweramp and exposes the same state and controls to the retained Web UI and a native Android phone client on the trusted local network.

Current version: `0.7.0`.

## Actual architecture

The repository contains two native Android application modules.

The `:app` module runs on HiBy R4. Its started-and-bound `RemotePlaybackService` owns:

- `PowerampClient`, the existing adapter for Poweramp's public Intent API;
- `PlaybackStateStore`, the thread-safe immutable state shared by the screen and network API;
- `RemoteApiServer`, a small bounded HTTP/WebSocket server implemented with Android/JDK socket APIs;
- `RemoteArtworkCache`, which encodes the already loaded artwork for authenticated delivery;
- `BrowserSessionStore`, a bounded in-memory cookie-session store derived from the existing API token;
- `RemoteNsdPublisher`, which advertises the bound API listener through Android NSD/mDNS;
- `WebUiAssets`, the dependency-free embedded HTML/CSS/JavaScript remote.

The R4 `MainActivity` is only the local presentation/control surface. It starts the service from a
visible app launch, binds while its UI is visible, and unbinds in `onStop()` without stopping the
Poweramp receivers, NSD advertisement, or network server. The Web UI is served by the same local
server and remains fully supported.

The `:phone` module is the first separate native Android client. It contains no Poweramp integration
and no server. `NsdDiscoveryClient` discovers/resolves API v1 services; `PairingStore` retains only a
verified server identity, service name, and Bearer token; `RemoteApiClient` submits authenticated
REST controls and artwork requests; and `RemoteWebSocket` receives complete event-driven state
snapshots. `RemoteClientController` reconnects with bounded exponential backoff and matches a known
R4 by its stable NSD identity instead of persisting an IP address.

There is no WebView, cloud service, polling state loop, or duplicate Poweramp integration path.

The service is an Android `connectedDevice` foreground service with an ongoing low-importance
notification. It returns `START_STICKY`, treats repeated start commands idempotently, and can retry
a previous server bind or Poweramp-install check. The notification's explicit Stop action stops the
server and receivers; `onDestroy()` closes sockets, WebSockets, browser sessions, executors,
artwork state, and Poweramp receivers. Removing the Activity from the foreground or locking the
screen does not invoke that shutdown path.

No partial wakelock or Wi-Fi lock is requested in version `0.7.0`: there is no target-device
evidence that either is necessary, and the foreground service plus TCP keepalive is the least
invasive baseline. Screen-off behavior still requires a real HiBy R4 verification pass before any
lock or battery-optimization exception is considered.

The HTTP server does not parse Poweramp broadcasts or send Poweramp broadcasts itself. It only reads `PlaybackStateStore` and submits validated commands through the existing `PowerampClient` command methods on Android's main thread.

No external server framework is used. Connections, request sizes, WebSocket frames, and concurrent WebSocket clients are bounded.

## Local API v1

The fixed port is `8765`. The server listens on the device's local interfaces and the Android UI shows the current IPv4 address, port, server status, WebSocket client count, and token.

When the socket is successfully bound, the server publishes `_poweramp-remote._tcp.` through
Android NSD on port `8765`. TXT attributes contain `api=1` and a random stable `id`; they never
contain the Bearer token. Publication stops with the listener. The phone resolves the current
address each launch/network recovery, so users do not enter or persist an R4 IP address.

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

Open `http://<R4-IP>:8765/` on a phone in the same LAN and enter the token shown on the R4. The
mobile-first page displays artwork; title, artist, and album; codec/file type, bit depth, sample
rate, and bitrate; source category and list position/size; elapsed position/duration; Previous,
state-aware Play/Pause, Next, and seek; Like, Dislike, rating reset; and binary Shuffle OFF/ON.
The compact portrait layout is sized to fit an ordinary phone viewport without vertical scrolling.

Releasing the seekbar sends exactly one `seek` command. Rating and shuffle buttons use the existing
`set_rating`, `shuffle_on`, and `shuffle_off` REST commands. Controls are rendered from full state
snapshots rather than locally pretending that Poweramp already changed. The page performs one
initial state request, receives subsequent state through WebSocket, and advances only the displayed
position locally between events; it does not poll the state endpoint. Artwork is loaded from
`/api/v1/artwork` with the session cookie.

If WebSocket disconnects, the page performs one authenticated state probe. A `401` returns it to the token form; a network outage uses bounded exponential reconnect backoff. Session expiry, logout, and session eviction close associated WebSockets so they cannot retain one of the four client slots.

### Native Android phone client

Install the `:phone` APK on a phone in the same IP network as the R4. On first launch it discovers
published Poweramp Remote servers through Android NSD/mDNS. The user copies the existing 43-character
Bearer token from the R4 screen; the client validates it with one `GET /api/v1/state` request and
persists it in private, backup-excluded preferences only after a successful authenticated response.

Subsequent launches look for the saved NSD server identity, resolve its current address, and open
the Bearer-authenticated event WebSocket automatically. Disconnects use bounded exponential retry
while discovery remains active, so a newly resolved address replaces a stale one. A reset action
deletes the local association. Wi-Fi Direct and Local Only Hotspot are intentionally not part of
version `0.7.0`; both devices must still share one IP network.

The first screen provides artwork; title, artist, and album; elapsed position and duration; codec,
file type, bit depth, sample rate, raw bitrate, source category, and raw list position/size;
Previous, state-aware Play/Pause, Next, one-shot seek, rating `0…5`, Like, Dislike, and binary
Shuffle. Commands use the existing REST route and wait for event snapshots rather than changing
confirmed state optimistically. Only the displayed elapsed position advances locally. The WebSocket
uses ping/pong only to detect a dead TCP connection; it never polls playback state.

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
- native phone UI using the same Bearer REST/artwork/WebSocket endpoints and NSD discovery.

Lyrics remain intentionally out of scope because the public Intent API exposes `lyricsState`, not the lyrics text.

## Security model

Version `0.7.0` remains a trusted-LAN prototype. Authentication prevents casual unauthorised
commands, but HTTP and `ws://` are not encrypted, so the initial token verification, cookie/token,
and metadata can be observed on an untrusted network. NSD advertises only API version and a public
random server identity, never the credential. Phone credentials use private preferences excluded
from cloud backup and device transfer; the phone never persists the resolved IP address. The Web UI
uses no browser storage for the token and retains its restrictive CSP, cookie/session bounds, and
same-origin checks. Do not expose port `8765` to the internet. Stronger pairing and transport
security are future roadmap work.

## Known semantic uncertainties

The exact unit of Poweramp `bitRate` and index base of `posInList` still require verification on HiBy R4. The network API deliberately preserves both raw values until that test is completed.

## Verification baseline

Version `0.7.0` keeps the full R4 API/auth/service/Web UI regression suite and adds server NSD
identity coverage plus phone parser, command, credential, reconnect, REST, and WebSocket loopback
coverage. The latest exact test, lint, clean-build, manifest, and APK results are recorded in
`STATUS.md`.

## Roadmap

Future planned work is maintained in [`ROADMAP.md`](ROADMAP.md).
