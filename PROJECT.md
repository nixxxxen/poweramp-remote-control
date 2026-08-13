# Poweramp Remote

## Project goal and versions

Poweramp Remote provides a reliable native Android Server for any compatible Android player device
with Poweramp, a retained same-origin Web UI, and a native Android Phone Client.

- Server: `0.8.0` (`versionCode 8`)
- Phone Client: `0.2.0` (`versionCode 8`)
- API: `v1` (unchanged)

The two application versions are deliberately independent. Both old application IDs shipped
`versionCode 7`, so both new counters currently equal `8`; this preserves Android upgrade
compatibility while later Server and Phone codes advance independently. The existing Android
`applicationId` values remain unchanged solely so upgrades preserve the Server API token and Phone
Client pairing. Current source namespaces and UI terminology are device-neutral.

## Architecture

The repository contains two native Android application modules.

### Server (`:app`)

The Server runs on the Poweramp device. One started-and-bound `RemotePlaybackService` owns:

- `PowerampClient`, the adapter for Poweramp's public Intent API;
- `PlaybackStateStore`, the thread-safe immutable state shared by UI and network API;
- `RemoteApiServer`, the bounded HTTP/WebSocket API listener;
- `RemoteArtworkCache`, which encodes already loaded artwork for authenticated delivery;
- `BrowserSessionStore`, the bounded in-memory same-origin cookie store;
- `RemoteNsdPublisher`, ordinary LAN NSD/mDNS publication;
- `RemoteWifiDirectPublisher`, pre-association Wi-Fi Direct DNS-SD publication;
- `WebUiAssets`, the dependency-free embedded HTML/CSS/JavaScript remote.

The local Activity is only a presentation/control surface. It starts the service from a visible app
launch, binds while visible, and unbinds in `onStop()` without stopping Poweramp receivers, either
discovery advertisement, the HTTP server, browser sessions, or WebSockets.

The service is an Android `connectedDevice` foreground service with an ongoing low-importance
notification. It returns `START_STICKY`; repeated starts are idempotent. The explicit Stop action
shuts down the server, discovery publishers, sessions, sockets, executors, artwork state, and
Poweramp receivers.

### Phone Client (`:phone`)

The Phone Client has no Poweramp integration and no server. It contains:

- `NsdDiscoveryClient` for ordinary LAN discovery/resolution;
- `WifiDirectConnectionClient` for known-server Wi-Fi Direct discovery and group negotiation;
- `PairingStore` for a verified stable Server identity, service name, and Bearer token;
- `RemoteApiClient` for REST state/control/artwork requests;
- `RemoteWebSocket` for complete event-driven state snapshots;
- `RemoteClientController` for LAN preference, direct fallback, and reconnect coordination.

There is no WebView, cloud service, playback polling loop, duplicate Server runtime, or second
Poweramp integration path.

## Connection model

### Initial pairing

Initial pairing remains LAN-only. Both devices join one IP network, the Phone Client discovers
`_poweramp-remote._tcp.` through Android NSD, and the user enters the existing 43-character Bearer
token shown by the Server. One authenticated `GET /api/v1/state` verifies the token before the
Phone Client stores the stable public Server `id`, service name, and token in private,
backup-excluded preferences. It never persists a resolved IP address.

### Preferred LAN connection

Every launch starts ordinary NSD. When the known `id` is resolved, the Phone Client opens the same
Bearer-authenticated API v1 WebSocket at the current LAN address. LAN remains preferred and retains
bounded exponential reconnect. Network callbacks restart discovery after loss or restoration so a
new DHCP address can replace a stale endpoint automatically.

### Wi-Fi Direct fallback

When a paired Server is not found through LAN NSD after a short grace period, the Phone Client
starts Wi-Fi Direct pre-association DNS-SD. The Server publishes:

- instance name `Poweramp Remote Server`;
- service type `_poweramp-remote._tcp`;
- TXT `api=1`;
- TXT `id=<stable public identity>`;
- TXT `port=8765`.

No token is published. The Phone Client ignores every record whose `id` is not the already verified
pairing identity or whose API/port is invalid. It initiates one P2P connection with minimum phone
group-owner intent so the Poweramp device is normally selected as group owner. This is a preference,
not a bypass: Android controls group negotiation and may show mandatory system confirmations on
either device. The app never auto-accepts or suppresses them.

After group formation, the phone uses `WifiP2pInfo.groupOwnerAddress` plus advertised port `8765`
as a transient endpoint. REST, artwork, WebSocket, Bearer auth, JSON, and controls are identical to
LAN API v1. A direct disconnect returns to LAN discovery and direct fallback. Restored network
availability restarts ordinary NSD automatically.

Direct-connect is bounded and user-visible. The Phone Client distinguishes permission required,
Location Mode disabled, Wi-Fi disabled, P2P unsupported, discovery/connect timeout, and a phone
selected as group owner. It offers the matching system-settings or retry action instead of looping
system dialogs. Wi-Fi Direct support is optional in both manifests.

For Android 13+ the apps declare/request `NEARBY_WIFI_DEVICES` with `neverForLocation`. Android
8–12L uses both `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`; Wi-Fi Direct discovery also
requires system Location Mode to be enabled. Both apps keep `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_STATE`. These permissions are used only for nearby direct connection, not to infer
physical location.

## Local API v1

The fixed port is `8765`. The listener binds local interfaces. Ordinary LAN NSD TXT contains only
`api=1` and stable `id`; Wi-Fi Direct additionally advertises the public listener port. Neither
transport advertises credentials.

External clients authenticate protected routes with:

```http
Authorization: Bearer <token>
```

The Server generates a 256-bit Base64URL token with `SecureRandom`, keeps it in private
backup-excluded preferences, displays it locally, and never accepts it in a query string.

The public page at `/` exchanges the token only through `POST /api/v1/session`. Successful
same-origin login creates a random 256-bit `HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/`
for 12 hours. Cookie-authenticated controls, logout, and WebSocket require exact same-origin
`Origin`; Bearer clients remain compatible without it. Sessions are in-memory and bounded to 16.

### Routes

| Method | Path | Result |
|---|---|---|
| `GET` | `/` | Public embedded Web UI plus `/app.css` and `/app.js` |
| `POST` | `/api/v1/session` | Exchange the displayed token for a browser session |
| `DELETE` | `/api/v1/session` | End the current browser session |
| `GET` | `/api/v1/state` | Current complete state JSON |
| `POST` | `/api/v1/control` | Validate and enqueue one command; success is `202` |
| WebSocket `GET` | `/api/v1/events` | Initial state, then complete event-driven snapshots |
| `GET` | `/api/v1/artwork` | Current JPEG artwork when available |

There is no CORS API, polling endpoint, URL credential, or WebSocket command channel.

### State contract

REST state and every WebSocket message use the same flat object with `apiVersion: 1`, monotonic
`revision`, Poweramp availability, metadata, playback position/state, rating, Like/Dislike, shuffle,
and artwork path. Existing field names and null behavior are unchanged.

Important semantic rules:

- unavailable optional values are JSON `null`;
- `playbackState` is `playing`, `paused`, `stopped`, or `null`;
- `rating` is `0…5` or `null`; `5` derives Like and `1` derives Dislike;
- `shuffle` is the binary view; `shuffleMode` preserves Poweramp's mode;
- `bitRate` preserves the Poweramp value without an API conversion;
- `positionInList` preserves the Poweramp value without an API index offset;
- artwork uses an authenticated relative path and `Cache-Control: no-store`.

The Phone Client UI formats bitrate as `кбит/с` using the same tolerant rule already used by the
Server UI (current bit/s representation or older values already in kbit/s) and presents an
available list position as `current / total`. It does not display diagnostic `list`/`raw` labels,
invent an unverified index offset, or change API v1 payloads.

### Controls

Accepted bodies remain:

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

Seek is an absolute integer second. Rating accepts `0…5`; Like is `5`, Dislike is `1`, clearing is
`0`. `202 Accepted` means the validated command was handed to the active Android client; confirmed
state arrives through Poweramp events and the next full snapshot.

### Event flow and UIs

Poweramp events such as `TRACK_CHANGED`, `STATUS_CHANGED`, and `PLAYING_MODE_CHANGED` update the
shared state. Artwork readiness and explicit position/rating updates can also increase revision.
WebSocket revisions are monotonic and slow clients may receive coalesced full snapshots. There is
no elapsed-second state polling; UIs only advance displayed time locally.

The embedded Web UI remains available on either reachable Server address and retains cookie
sessions, CSP, Origin checks, artwork, transport, seek, rating, Like/Dislike, and shuffle. The native
Phone Client uses the same Bearer routes and waits for confirmed snapshots rather than making
authoritative optimistic state changes.

## Security model

This remains a trusted-local-link prototype. Wi-Fi Direct provides link encryption, but API HTTP
and `ws://` are not end-to-end encrypted; LAN traffic and credentials can be observed on an
untrusted network. Never expose port `8765` to the internet. Discovery advertises public identity,
API version, and (for P2P) listener port only. Credentials remain private and backup-excluded.
Stronger pairing and application-layer transport security remain future work.

## Known uncertainties and real-device requirements

The exact Poweramp `bitRate` unit and `posInList` index base still require device verification;
API v1 intentionally preserves both. Wi-Fi Direct behavior also varies by vendor: the Server must
be selected as group owner for the current IPv4 client path, system approval may be required after
prior pairing, and dual LAN/P2P routing must be checked on representative Android 8–16 devices.

Exact completed automation and the remaining hardware matrix are recorded in `STATUS.md`.

## Roadmap

Future work is maintained in [`ROADMAP.md`](ROADMAP.md).
