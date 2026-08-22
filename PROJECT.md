# Poweramp Remote

## Project goal and versions

Poweramp Remote provides a reliable native Android Server for any compatible Android player device
with Poweramp, a retained same-origin Web UI, and a native Android Phone Client.

- Server: `0.10.2` (`versionCode 13`)
- Phone Client: `0.5.0` (`versionCode 14`)
- API: `v1` (unchanged)

The two application versions are deliberately independent. Both old application IDs shipped
`versionCode 7`; the Server counter is now `13` and the Phone counter is `14`. This preserves
Android upgrade compatibility while later Server and Phone codes continue to advance independently. The existing Android
`applicationId` values remain unchanged solely so upgrades preserve the Server API token and Phone
Client pairing. Current source namespaces and UI terminology are device-neutral.

## Architecture

The repository contains two native Android application modules.

### Server (`:app`)

The Server runs on the Poweramp device. One started-and-bound `RemotePlaybackService` owns:

- `PowerampClient`, the adapter for Poweramp's public Intent API;
- `SystemMediaVolumeController`, the event-driven `AudioManager.STREAM_MUSIC` adapter;
- `PlaybackStateStore`, the thread-safe immutable state shared by UI and network API;
- `RemoteApiServer`, the bounded HTTP/WebSocket API listener;
- `RemoteArtworkCache`, which encodes already loaded artwork for authenticated delivery;
- `BrowserSessionStore`, the bounded in-memory same-origin cookie store;
- `PairingSecretStore`, the one-active-offer owner for short-lived, one-time QR pairing;
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

The Phone Client has no Poweramp integration and no server. One started-and-bound
`PhoneConnectionService` is the combined `connectedDevice|mediaPlayback` foreground owner of:

- `NsdDiscoveryClient` for ordinary LAN discovery/resolution;
- `WifiDirectConnectionClient` for known-server Wi-Fi Direct discovery and group negotiation;
- `PairingStore` for a verified stable Server identity, device/service names, and Bearer token;
- `RemoteApiClient` for REST state/control/artwork requests;
- `RemoteWebSocket` for complete event-driven state snapshots;
- `RemoteClientController` for LAN preference, direct fallback, and reconnect coordination;
- `PairingRequestState` for binder-independent QR/manual requests delivered to that controller;
- `PlaybackUiSnapshot`, the service-owned position anchor replayed to a rebound Activity;
- `RemoteSessionPlayer`, a Media3 `SimpleBasePlayer` facade over the remote state and commands;
- one Media3 `MediaSession` exposed to Android System UI, lock screen, and compatible Wear OS
  controllers.

`MainActivity` binds only while visible and is a playback-only presentation/control surface with a
small native menu for Settings/About. `PlayerDevicesActivity` owns saved-device diagnostics,
QR/manual pairing, re-pair/forget actions, and recoverable permission/settings actions.
`SettingsActivity` and `AboutActivity` are presentation-only and never own or replace the connection
runtime. No Activity lifecycle cancels P2P negotiation,
removes a group, closes the P2P channel, stops NSD, or closes the API WebSocket. The notification's
explicit Stop action and final service destruction are the teardown paths.

The Phone does not play or decode audio and never changes its own volume. The custom player forwards
play, pause, previous, next, and seek to API v1, while metadata, artwork, playback state, duration,
and position come from the existing WebSocket snapshots. There is no fake ExoPlayer, audio-focus
request, WebView, cloud service, playback polling loop, duplicate Server runtime, or second Poweramp
integration path.

Phone presentation resources use complete English fallback values and a complete Russian
translation. The persisted choices are `system`, `ru`, and `en` in a private preference file that is
separate from pairing storage. System mode resolves a primary Russian system locale to Russian and
all other system locales to English. Android 13+ also receives the selection through platform
`LocaleManager` and advertises `en`/`ru` with `localeConfig`; API 26–32 uses a per-component
configuration context. Every Activity and scanner prompt receives that context, while the existing
service rebuilds only its localized notification and channel copy when the choice changes. The
process default locale is never changed, so protocol parsing, JSON, NSD, WebSocket, Wi-Fi Direct,
codec normalization, identity, and credential handling retain their `Locale.ROOT` behavior.

The Server has no language selector: its Android Activity, foreground notification/channel, and
embedded Web UI are English-only. API v1 retains its historical `sourceCategoryName` wire values;
native and Web presentation localize/format the accompanying unchanged numeric `sourceCategory`
instead of treating that compatibility field as UI copy.

## Connection model

### Initial pairing

The Server issues one active pairing offer with a two-minute lifetime and renders it as a QR code.
The address-free `powerampremote://pair` payload contains API version `1`, the persistent public
Server `id`, a random 256-bit one-time secret, and a non-secret player-device name. It contains
neither the persistent Bearer token nor a LAN/P2P address.

After **Pair new player** or **Re-pair**, the Phone Client scans and strictly validates that payload,
then targets only its exact public Server `id`. Ordinary LAN NSD remains the first search path. If
the identity is not found during the LAN grace period, the same pre-association Wi-Fi Direct DNS-SD
path is used for this scanned identity; Android/OEM permissions, Location Mode, group-owner choice,
and approval remain mandatory where applicable. Once an endpoint is reachable, unauthenticated
`POST /api/v1/pair` atomically consumes the secret and returns the existing persistent API
credential. Reuse, expiry, a mismatched identity, or a mismatched API version is rejected. Expected
rejections and unexpected request-local failures are logged without request bodies, secrets, or
credentials. The request body is parsed by Android `org.json`, not a regexp: field order and
unknown fields are irrelevant, while required raw types, API version, canonical Server identity,
and canonical secret encoding are explicit. Malformed/missing/wrong-type input returns a local
`400`; reuse/expiry/mismatch returns `401`. The confirmed Android ICU static-regexp initializer
failure is therefore absent, parser nesting failure is contained locally, and unexpected runtime
failures remain behind the diagnostic request boundary.

The scanner result is submitted as a private explicit start command to the already existing
`PhoneConnectionService`. A service-owned request handoff keeps the work independent of the
Activity's asynchronous bind state, including cold launch and the scanner Activity's stop/start
transition. Each scanner launch has a UUID preserved with Activity state. The handoff deduplicates
pending/active delivery of that UUID and replays its exact completed success/failure without a
second exchange. A deliberate new launch has a new UUID, so rescanning an old one-time code still
reaches Server and receives the normal stale-code rejection. Devices without a usable camera can
instead enter the existing 43-character Bearer token. That fallback discovers Servers through
ordinary LAN NSD, verifies the token with the
unchanged authenticated `GET /api/v1/state`, and saves the verified identity; it does not accept an
IP address or add another connection path. QR remains the only initial pairing route that can target
Wi-Fi Direct before credentials exist because its payload supplies the stable Server identity.

Only after a successful exchange does Phone replace its saved association with the stable Server
`id`, service/device names, and Bearer credential in private backup-excluded preferences. It never
persists a resolved address. Existing `0.3.0` preferences migrate in place by using the saved NSD
service name as the initial device label. The credential commit precedes authenticated connection;
a recreated service loads the same association, resumes existing discovery for that exact ID, and
uses the unchanged reconnect/WebSocket path.

### Preferred LAN connection

Every launch starts ordinary NSD. When the known `id` is resolved, the Phone Client opens the same
Bearer-authenticated API v1 WebSocket at the current LAN address. LAN remains preferred and retains
bounded exponential reconnect. Recovery observes Wi-Fi/Ethernet networks rather than merely the
system default network, so cellular availability cannot keep a stale LAN endpoint alive after the
shared network disappears. Loss invalidates the transient LAN endpoint and restarts NSD/direct
fallback; a new DHCP address can replace it automatically.

### Wi-Fi Direct fallback

When a paired Server, or the exact identity from an active QR offer, is not found through LAN NSD
after a short grace period, the Phone Client starts Wi-Fi Direct pre-association DNS-SD. The Server
publishes:

- instance name `Poweramp Remote Server`;
- service type `_poweramp-remote._tcp`;
- TXT `api=1`;
- TXT `id=<stable public identity>`;
- TXT `port=8765`.

No token or pairing secret is published. The Phone Client ignores every record whose `id` is not
the saved or currently scanned target identity, or whose API/port is invalid. It initiates one P2P connection with minimum phone
group-owner intent so the Poweramp device is normally selected as group owner. This is a preference,
not a bypass: Android controls group negotiation and may show mandatory system confirmations on
either device. The app never auto-accepts or suppresses them.

After group formation, the phone uses `WifiP2pInfo.groupOwnerAddress` plus advertised port `8765`
as a transient endpoint. REST, artwork, WebSocket, Bearer auth, JSON, and controls are identical to
LAN API v1. A direct disconnect returns to LAN discovery and direct fallback. Restored network
availability restarts ordinary NSD automatically.

Publication does not rely on opening the system Wi-Fi Direct settings screen. After registering its
local DNS-SD record, the Server starts and refreshes `discoverPeers()` so it participates in the
framework peer-discovery/listen cycle. The Phone explicitly runs `discoverPeers()` before adding its
DNS-SD request and calling `discoverServices()`. Both sides observe P2P state, discovery, peer-list,
connection, and channel-loss callbacks for their service lifetime. Stopped or failed discovery is
retried with bounded delay; a confirmed group loss returns to LAN-first discovery and direct fallback.

Every fresh LAN-to-direct transition discards stale Phone discovery requests/connections and closes
the old `WifiP2pManager.Channel` where supported before creating a new channel. The Server watches
LAN transport changes, clears/re-adds its local DNS-SD service, restarts peer discovery, and likewise
reinitializes a failed channel. This repairs framework state that can otherwise remain stale until a
process restart while retaining LAN preference and the same stable identity.

Direct-connect is bounded and user-visible. The Phone Client distinguishes permission required,
Location Mode disabled, Wi-Fi disabled, P2P unsupported, discovery/connect timeout, and a phone
selected as group owner. It offers the matching system-settings or retry action instead of looping
system dialogs. An initial approval/connect failure remains user-retryable so mandatory prompts are
not looped; after a previously confirmed direct group, a temporary disconnect is automatically
retried. Wi-Fi Direct support is optional in both manifests.

For Android 13+ the apps declare/request `NEARBY_WIFI_DEVICES` with `neverForLocation`. Android
8–12L uses both `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`; Wi-Fi Direct discovery also
requires system Location Mode to be enabled. Both apps keep `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_STATE`. These permissions are used only for nearby direct connection, not to infer
physical location.

The Phone service declares both `FOREGROUND_SERVICE_CONNECTED_DEVICE` and
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, shows one ongoing media/connection notification, exports the
standard Media3 service binding, and returns `START_STICKY`. Its private in-process Activity binder
does not define an IPC transaction surface. No keep-screen-on flag, partial wakelock, or Wi-Fi lock
is used by either connection path.

## Local API v1

The fixed port is `8765`. The listener binds local interfaces. Ordinary LAN NSD TXT contains only
`api=1` and stable `id`; Wi-Fi Direct additionally advertises the public listener port. Neither
transport advertises credentials.

External clients authenticate protected routes with:

```http
Authorization: Bearer <token>
```

The Server generates a 256-bit Base64URL token with `SecureRandom`, keeps it in private
backup-excluded preferences, and never places it in the QR or a query string. The local Server UI
does not display it; an explicit sensitive-clipboard action remains solely so the unchanged Web UI
and external API v1 clients can obtain their credential.

The public page at `/` exchanges the token only through `POST /api/v1/session`. Successful
same-origin login creates a random 256-bit `HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/`
for 12 hours. Cookie-authenticated controls, logout, and WebSocket require exact same-origin
`Origin`; Bearer clients remain compatible without it. Sessions are in-memory and bounded to 16.

### Routes

| Method | Path | Result |
|---|---|---|
| `GET` | `/` | Public embedded Web UI plus `/app.css` and `/app.js` |
| `POST` | `/api/v1/session` | Exchange a browser-supplied credential for a session |
| `DELETE` | `/api/v1/session` | End the current browser session |
| `POST` | `/api/v1/pair` | Consume the active one-time QR secret and issue credentials |
| `GET` | `/api/v1/state` | Current complete state JSON |
| `POST` | `/api/v1/control` | Validate and enqueue one command; success is `202` |
| WebSocket `GET` | `/api/v1/events` | Initial state, then complete event-driven snapshots |
| `GET` | `/api/v1/artwork` | Current JPEG artwork when available |

There is no CORS API, polling endpoint, URL credential, or WebSocket command channel.

### State contract

REST state and every WebSocket message use the same flat object with `apiVersion: 1`, monotonic
`revision`, Poweramp availability, metadata, playback position/state, rating, Like/Dislike, shuffle,
artwork path, and optional player-device volume fields. Existing field names and null behavior are
unchanged, so older API v1 clients can ignore the appended fields.

Important semantic rules:

- unavailable optional values are JSON `null`;
- `playbackState` is `playing`, `paused`, `stopped`, or `null`;
- `rating` is `0…5` or `null`; `5` derives Like and `1` derives Dislike;
- `shuffle` is the binary view; `shuffleMode` preserves Poweramp's mode;
- `bitRate` preserves the Poweramp value without an API conversion;
- `positionInList` preserves the Poweramp value without an API index offset;
- `volume` and `volumeMax` are integer `AudioManager.STREAM_MUSIC` steps or `null`;
- `volumeControlAvailable` reports whether Server can change that stream;
- artwork uses an authenticated relative path and `Cache-Control: no-store`.

The Phone Client UI formats bitrate as `kbps` in English and `кбит/с` in Russian, using the same
tolerant rule as Server presentation (current bit/s representation or older values already in
kbit/s). Bit depth and sample-rate units plus decimal point/comma also follow the selected Phone
language. Every presentation surface shows an available list position as the unchanged raw
`current / total`; it does not display diagnostic `list`/`raw` labels, invent an unverified index
offset, or change API v1 payloads.

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
{"action":"set_volume","value":7}
```

Seek is an absolute integer second. Rating accepts `0…5`; Like is `5`, Dislike is `1`, clearing is
`0`. Volume is a non-negative integer step and is validated against the current `volumeMax` by the
Server's system-volume adapter. `202 Accepted` means the validated command was handed to the active
Android client; confirmed state arrives through the next full snapshot.

The official Poweramp Intent API snapshot audited for this release (repository source through
Poweramp build `1026-beta`) exposes no public volume command. Server therefore controls only the
player device's Android media stream through `AudioManager.STREAM_MUSIC`; no internal Poweramp DSP
constant is assumed and Phone never changes its local stream.

### Event flow and UIs

Poweramp events such as `TRACK_CHANGED`, `STATUS_CHANGED`, and `PLAYING_MODE_CHANGED` update the
shared state. Artwork readiness and explicit position/rating updates can also increase revision.
Android system-setting notifications update media volume, including changes from the player-device
hardware buttons. WebSocket revisions are monotonic and slow clients may receive coalesced full
snapshots. There is no elapsed-second state polling; UIs and MediaSession only advance displayed
time locally from a confirmed position anchor.

On Phone, the WebSocket reader records monotonic time as soon as a complete state frame is received,
before posting it to the main looper. `PhoneConnectionService` creates one playback anchor from the
confirmed remote position plus that receipt time. The rebound UI and `RemoteSessionPlayer` consume
the same object, and a listener added after Activity resume/rebind receives the original anchor
rather than a stale position re-anchored at callback time. Playing extrapolates; pause, track
change, seek, and reconnect establish fresh confirmed anchors; disconnect freezes the current
anchor without losing its extrapolated millisecond fraction. This keeps the player screen and
notification aligned without network or UI polling.

The embedded Web UI remains available on either reachable Server address and retains cookie
sessions, CSP, Origin checks, artwork, transport, seek, rating, Like/Dislike, shuffle, and a compact
remote-volume slider. The native Phone Client adds the same volume control and a MediaSession while
using the same Bearer routes and waiting for confirmed snapshots rather than making authoritative
optimistic state changes.

The Phone main screen is deliberately player-only and non-scrolling on a typical smartphone:
rounded artwork receives the flexible space; Previous/Play-Pause/Next remain primary; rating,
Like/Dislike, and Shuffle are compact secondary controls with selected states, ripple feedback, and
haptics. Seek and volume use player-specific tracks/thumbs, and codec/file type, bit depth, sample
rate, and bitrate use muted metadata chips. A symmetric left menu button opens the small native
Settings/About menu while the existing right Player devices button remains direct. Connection
transport, API diagnostics, pairing,
re-pair, and forget actions live on the separate **Player devices** screen. The current data model
exposes a generic saved-device snapshot but intentionally persists only one slot in this release.
All Phone presentation Activities use one edge-to-edge View path and add system-bar plus
display-cutout insets to their root padding. The player keeps required controls in a fixed no-scroll
budget and gives only
the artwork the remaining height; volume remains represented by a disabled placeholder before its
first remote snapshot. The artwork container measures to the smaller available dimension so its
rounded image is always square and `centerCrop` never stretches it. The QR scanner uses its own
non-exported capture Activity: portrait is the default, while a caller already configured in
landscape requests scanner-only landscape. The platform `SeekBar` continues to own touch/accuracy
semantics while its playback track is rendered at `8dp` with a compact `14dp` thumb.

## Security model

This remains a trusted-local-link prototype. Wi-Fi Direct provides link encryption, but API HTTP
and `ws://` are not end-to-end encrypted; LAN traffic and the one-time exchange/credentials can be
observed on an untrusted network. Never expose port `8765` to the internet. Discovery advertises
public identity, API version, and (for P2P) listener port only. The QR never carries an address or
persistent credential; its secret is random, short-lived, one-time, and invalidated atomically.
Persistent credentials remain private and backup-excluded. Application-layer encrypted transport
remains future work.

## Known uncertainties and real-device requirements

The exact Poweramp `bitRate` unit and `posInList` index base still require device verification;
API v1 intentionally preserves both. Wi-Fi Direct behavior also varies by vendor: the Server must
be selected as group owner for the current IPv4 client path, system approval may be required after
prior pairing, and dual LAN/P2P routing must be checked on representative Android 8–16 devices.

Exact completed automation and the passed Server `0.10.2` / Phone `0.5.0` in-place hardware matrix
are recorded in `STATUS.md`. Broader Android/OEM coverage remains ongoing rather than a blocker for
this release.

## Roadmap

Future work is maintained in [`ROADMAP.md`](ROADMAP.md).
