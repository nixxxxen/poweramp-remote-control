# Poweramp Remote

## Project goal and versions

Poweramp Remote provides a reliable native Android Server for any compatible Android player device
with Poweramp, a retained same-origin Web UI, and a native Android Phone Client.

- Server: `0.10.2` (`versionCode 13`)
- Phone Client: `0.6.0` (`versionCode 15`)
- API: `v1` (unchanged)

The two application versions are deliberately independent. Both old application IDs shipped
`versionCode 7`; the Server counter is now `13` and the Phone counter is `15`. This preserves
Android upgrade compatibility while later Server and Phone codes continue to advance independently. The existing Android
`applicationId` values remain unchanged solely so upgrades preserve the Server API token and Phone
Client pairing. Current source namespaces and UI terminology are device-neutral.

## Architecture

The repository contains two native Android application modules.

### Server (`:app`)

The Server runs on the Poweramp device. One started-and-bound `RemotePlaybackService` owns:

- `PowerampClient`, the sole adapter for Poweramp's public Intent commands and album-art provider;
- `PowerampLibrarySource` plus `AndroidPowerampLibraryProvider`, the lazy, bounded adapter for the
  public Poweramp ContentProvider;
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

The main player also owns a presentation-only dynamic artwork theme. It samples the already decoded
current artwork on a dedicated executor, deterministically selects two or three distinct color
families, and normalizes them into a bounded dark-interface range. A process-local 12-entry LRU is
keyed by the stable paired Server identity plus `RemoteState.artworkKey()`; revisions, transient LAN/
P2P endpoints, Activity rebind, and playback-position updates therefore do not repeat analysis.
Concurrent requests for one key are coalesced, the cache stores only compact color values, and
generation checks prevent late results from an older track from changing the current screen.

The same player presentation now owns one artwork-navigation coordinator shared by the existing
Previous/Next buttons and horizontal gestures that begin inside the square artwork container. The
coordinator keeps only the latest visual intent while every accepted action still sends exactly one
existing `previous` or `next` command through the bound service. Complete remote snapshots remain
authoritative: metadata and track identity are never invented optimistically, external track changes
use a neutral transition, and generation-bound artwork results can update only the latest confirmed
`trackIdentity()`/`artworkKey()` pair.

The main player also owns small presentation-only motion policies for Previous/Next, Play/Pause,
Like/Dislike, Shuffle, and playback progress. Play/Pause and Shuffle use custom tint-aware Drawables
whose internal glyph geometry morphs; Previous/Next nudge in the requested direction; and Like and
Dislike wrap only their vector glyphs in one bounded pulse. Animated playback buttons use rounded
pressed-state surfaces instead of a foreground ripple, while padding and touch targets stay fixed.
The policies remember confirmed remote values, ignore duplicate renders, retarget one active
animator from its current progress, and apply replay/disabled-animation state immediately.

The right top-bar action remains the direct entry to `PlayerDevicesActivity`, but its compact pill
now summarizes the existing controller status as LAN, Wi-Fi Direct, Connecting, or Disconnected.
It is callback-driven from the existing `PlayerDeviceSnapshot`/`RemoteClientController.Status` and
never exposes endpoint, API, or Server diagnostics on the player. The four metadata chips retain
their formatted text while a pure raw-value policy selects fixed muted codec, bit-depth, sample-rate,
and bitrate families. Those styles are cached per Activity and never depend on the artwork palette.

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
The additive Library/Search/Queue routes below deliberately accept only the persistent Bearer
credential, not the browser-session cookie; the embedded Web UI has no Library integration yet.

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
| `GET` | `/api/v1/library` | Access state, routes, limits, and queue capabilities |
| `GET` | `/api/v1/library/tracks` | Paged All tracks rows |
| `GET` | `/api/v1/library/artists` | Paged Artists rows |
| `GET` | `/api/v1/library/artists/{id}/tracks` | Paged tracks for one artist |
| `GET` | `/api/v1/library/albums` | Paged Albums rows |
| `GET` | `/api/v1/library/albums/{id}/tracks` | Paged tracks for one album |
| `GET` | `/api/v1/library/folders` | Paged plain-folder rows |
| `GET` | `/api/v1/library/folders/{id}/tracks` | Paged direct tracks in one plain folder |
| `GET` | `/api/v1/library/folder-tree/{id}/folders` | Paged hierarchy children; `id=0` is the root |
| `GET` | `/api/v1/library/folder-tree/{id}/tracks` | Paged direct tracks in a hierarchy folder |
| `GET` | `/api/v1/library/playlists` | Paged Playlists rows |
| `GET` | `/api/v1/library/playlists/{id}/tracks` | Paged playlist entries |
| `GET` | `/api/v1/search?q={query}` | Paged server-side Poweramp track search |
| `GET` | `/api/v1/queue` | Paged current queue in provider order |
| `POST` | `/api/v1/library/play` | Revalidate and enqueue one allowlisted `OPEN_TO_PLAY` target |
| `GET` | `/api/v1/library/artwork/tracks/{id}` | Lazy authenticated JPEG for track artwork |

There is no CORS API, polling endpoint, URL credential, or WebSocket command channel.

### Library, Search, and Queue contract

This is the Server foundation for the Library/Queue series. It adds no Phone or Web UI, second
service/Poweramp connection, library WebSocket stream, or local copy of Poweramp data. Every request
queries only its current category/page through the adapter owned by `RemotePlaybackService`. All
returned cursors and artwork streams close on success, failure, or cancellation.

The official upstream audit used `maxmpz/powerampapi` master commit
[`60cac5a24348bde03e0619c0ab891bd752750b92`](https://github.com/maxmpz/powerampapi/commit/60cac5a24348bde03e0619c0ab891bd752750b92)
(2026-09-01), including
[`PowerampAPI.java`](https://github.com/maxmpz/powerampapi/blob/master/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/PowerampAPI.java),
[`TableDefs.kt`](https://github.com/maxmpz/powerampapi/blob/master/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/TableDefs.kt), the Intent API readme, and the official example. The public data authority is
`com.maxmpz.audioplayer.data`. This foundation uses these public paths:

- `files`, `artists`, `artists/{id}/files`, `albums`, and `albums/{id}/files`;
- `folders`, `folders/{id}/files`, `folders_hier/{id}/subfolders`, and
  `folders_hier/{id}/files`, with documented hierarchy root ID `0`;
- `playlists`, `playlists/{id}/files`, and `queue`;
- exact item forms `files/{id}`, `playlists/{playlistId}/files/{entryId}`, and
  `queue/{entryId}` when validating play targets.

Provider projections are an explicit subset of public `TableDefs` columns:

| Rows | Requested Poweramp columns |
|---|---|
| tracks and search | `folder_files._id`, `folder_files.name`, `title_tag`, `artist`, `album`, `folder_files.duration` |
| artists | `artists._id`, `artist`, `artists.num_files`, `artists.duration` |
| albums | `albums._id`, `album`, `albums.num_files`, `albums.duration` |
| plain folders | `folders._id`, `folders.name`, `folders.parent_id`, `folders.num_files`, `folders.duration` |
| hierarchy folders | the same identity/name/parent plus `folders.hier_num_files`, `folders.hier_duration` |
| playlists | `playlists._id`, `playlists.playlist`, `playlists.num_files`, `playlists.duration` |
| playlist entries | track columns above plus `playlist_entries._id` |
| queue entries | track columns above plus `queue._id` |

Aliases used after the query are Server-local names, not assumed provider columns. Browsing sends
no selection or selection arguments; search uses the fixed parameterized selection described below.
Neither sends a sort expression. In particular, the adapter does not request private filesystem
paths, queue `sort`, timestamps, or undocumented metadata. It acquires an unstable
`ContentProviderClient` for one attempt and closes the Cursor before the client. Provider death
therefore does not establish a stable dependency that lets Android kill Server with Poweramp;
`DeadObjectException`/`RemoteException` become a controlled unavailable response without retry.

The public list-query parameters are integer `lim` (SQL limit) and integer `shf` (shuffle mode);
this foundation sends only `lim`, because it neither requests nor invents shuffled ordering.
The ContentProvider contract does not document an offset parameter. API `limit` defaults to `25`
and has a hard maximum of `100`.
`pageToken` is a 24-character opaque Base64URL capability kept in memory for five minutes and bound
to the exact category, container, and search. For continuation, Server requeries the same URI with
a bounded increasing `lim`, skips only inside the returned Cursor, and never retains a Cursor
between HTTP requests. The hard provider window is `1000` rows (`1001` only to detect more data).
When more rows exist beyond that boundary, `nextPageToken` is `null` and `truncated` is `true`;
otherwise `truncated` remains `false`. The last page is clipped to the remaining window even if
`limit` does not divide 1000; the extra detection row is never returned. This explicit limitation
avoids inventing undocumented SQL offset/keyset semantics. Since each page requeries current
provider order, library edits between requests can cause repeats or omissions; tokens do not freeze
a database snapshot. Tokens disappear on eviction or service/process
shutdown. Invalid, expired, or cross-query tokens return `400 invalid_page_token`.
Response `offset` is only the number of provider rows already skipped for that opaque token; clients
cannot submit it, and it is not claimed to be a Poweramp/SQL offset.

Page JSON is:

```json
{
  "category": "queue",
  "limit": 25,
  "offset": 0,
  "items": [],
  "nextPageToken": null,
  "truncated": false
}
```

Each item has stable nullable fields `type`, `id`, `entryId`, `parentId`, `title`, `artist`, `album`,
`durationMilliseconds`, `trackCount`, `artwork`, `play`, and `current`. `id` is the underlying
Poweramp track/category ID. Playlist and queue rows additionally preserve their distinct public
`playlist_entries._id` or `queue._id` as `entryId`; duplicate uses of one track therefore stay
distinct. `parentId` identifies the requested containing category for track/playlist-entry rows and
the documented parent folder for folder rows; a folder parent may be root `0`. Duration comes only
from documented millisecond columns; counts only from `num_files`/`hier_num_files`. Missing, null,
invalid, or unexpectedly
absent optional columns become JSON `null`; a row without its required positive ID is omitted and
unexpected columns are ignored. Text is bounded to 1000 UTF-16 code units. No filesystem path,
source URL, raw provider URI, or database-only value is exposed.

Track artwork uses only authenticated relative path
`/api/v1/library/artwork/tracks/{trackId}`. Server constructs the confirmed provider URI
`content://com.maxmpz.audioplayer.aa/files/{folder_files._id}`, decodes with bounded dimensions,
closes each stream, limits encoded output to 5 MiB, and permits two concurrent lazy loads.
The public album-art provider also documents `hd` and `dl` parameters; Server sends neither, so it
retains provider defaults and never triggers an artwork download. Artists/albums/folders/playlists
do not claim artwork until exact entity-art semantics are verified.

Search accepts required `q`, trimmed to 1–160 non-control characters; `q`, `limit`, and `pageToken`
are its only parameters. It queries `/files?lim=N` using the normal track projection and fixed
`LIKE ? ESCAPE '!'` selection over `title_tag`, `folder_files.name`, `artist`, and `album`.
The contains-pattern is supplied only through four bound `selectionArgs`; `%`, `_`, and `!` in
user input are escaped as literals. Matching executes in Poweramp before the provider limit, not
over a locally downloaded page or database copy. Results are tracks, not separate artist/album
entities; ordering and case/diacritic matching remain provider-defined.

On Poweramp `1025004-fa3ec08671d`, the maintainer confirmed a known query returns the matching track
and a nonexistent query returns an empty page. Device logcat also confirmed why `folder_files.name`
must be qualified: bare `name` is ambiguous in the `/files` join with `folders`.
The obsolete `/search?flt` path is excluded from the allowlist and is never used as fallback:
it crashed this Poweramp build's `RestProvider.query` even with the default projection, and upstream
commit `8ca1dcbfbd573c221733bba34f4a20d9ebe2482f` marks `PARAM_FILTER` as no longer used.
Every HTTP search owns an independent cancellation and response; there is no shared result cache
for an old request to overwrite. Server shutdown cancels active provider requests. Search text is
never logged.

`POST /api/v1/library/play` accepts only these shapes:

```json
{"type":"track","id":41}
{"type":"album","id":8}
{"type":"playlist","id":7}
{"type":"playlist_entry","playlistId":7,"entryId":99}
{"type":"queue_entry","entryId":12}
```

Unknown/missing/extra fields, non-integer/non-positive IDs, and every supplied URI are rejected.
Server first requeries the exact allowlisted target with `lim=1`, confirms it still exists, builds
the documented URI itself, then sends command `20` (`OPEN_TO_PLAY`) through the existing
`PowerampClient` receiver path. It never opens arbitrary `file://`, `http(s)://`, or third-party
`content://` data. `202` means the target reached the active command path; playback confirmation
still arrives through normal Poweramp events.

Queue Cursor order is retained without an invented sort expression. When the playback snapshot
reports category `QUEUE` and a positive public `track.id`, a row is `current=true` only for an exact
match with that row's `queue._id`; `folder_files._id` is insufficient because duplicates are legal.
Without such a Queue snapshot, every row has `current=null`, not a guessed `false`. The combination
of the documented category/current-track ID and Queue entry-ID contracts supports this mapping, but
its behavior with duplicate entries remains an explicit real-device check. Capabilities are:

```json
{
  "read": true,
  "playExisting": true,
  "add": false,
  "remove": false,
  "reorder": false,
  "playNext": false
}
```

The official example demonstrates Add to Queue by inserting public `folder_file_id` and `sort`
fields into `queue`, then sending `ACTION_RELOAD_DATA`. That is evidence for a separate mutation
task, not authorization to expose it now. The audited public repository has no corresponding
documented Remove, Reorder, or Play Next contract. Its MediaSession documentation also excludes
`AddQueueItem` and `RemoveQueueItem`. No mutation is implemented here, and no internal database,
hidden intent, Accessibility/UI automation, or unsupported MediaSession queue operation is used.

### Poweramp data permission

Android 8+ Poweramp data queries require approval through documented
`ACTION_ASK_FOR_DATA_PERMISSION`, with this application's package in extra `pak`. The official
contract accepts the explicit Poweramp API receiver via `sendBroadcast` or API Activity via
`startActivity`; Server chooses the Activity only from a foreground user click so the confirmation
is visible and attributable. An HTTP request never launches UI. `SecurityException` becomes status `permission_required`; protected data routes
return `403 poweramp_data_permission_required`, while `GET /api/v1/library` remains a controlled
`200` capability/status response. Missing Poweramp, a null provider, and other provider failures
become sanitized `503` responses and never terminate Server.

The capability object fields are `status`, `permissionRequired`, `permissionRequestAvailable`,
`routes`, `pagination`, and `queueCapabilities`. Status is one of `unknown`, `available`,
`poweramp_missing`, `permission_required`, `provider_unavailable`, or `provider_error`.
Data-route failures expose only stable codes `poweramp_data_permission_required`,
`poweramp_unavailable`, `poweramp_provider_unavailable`, or `poweramp_provider_error`.
Malformed category/ID/query/limit input is `400 invalid_library_request`; a missing revalidated play
target is `404 library_item_not_found`. An unexpected integration defect is contained as sanitized
`500 library_internal_error`; its exception message and provider/search URI are never sent or logged.

The existing Server Activity shows access state. Only an explicit user press on **Request Poweramp
library access** starts the official explicit Poweramp API Activity. The upstream example notes this
works only while the Poweramp process is alive. Returning to Server, binding, or explicit refresh
probes again, so newly granted access is recognized without restarting the foreground service.
Mandatory Poweramp/system confirmation is never bypassed.

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

While Main is visible and confirmed playback is advancing, its existing platform `SeekBar` uses
millisecond presentation units and a lifecycle-bound `Choreographer` callback to sample that same
service-owned anchor. It does not accumulate frame deltas or create a second remote position model.
Small same-track snapshot differences are reconciled by a bounded 280 ms visual offset that converges
to the new anchor; a large discontinuity, pause/resume, track change, seek timeout/failure, or
reconnect applies authoritative state immediately. Manual drag owns the thumb until release, then
the existing integer-second seek command and bounded pending-seek policy take precedence over stale
snapshots. `onStop()` removes every frame callback; with animator scale disabled, accurate local
sampling continues at a conservative 250 ms cadence.

The embedded Web UI remains available on either reachable Server address and retains cookie
sessions, CSP, Origin checks, artwork, transport, seek, rating, Like/Dislike, shuffle, and a compact
remote-volume slider. The native Phone Client adds the same volume control and a MediaSession while
using the same Bearer routes and waiting for confirmed snapshots rather than making authoritative
optimistic state changes.

The Phone main screen is deliberately player-only and non-scrolling on a typical smartphone:
rounded artwork receives the flexible space; Previous/Play-Pause/Next remain primary; rating,
Like/Dislike, and Shuffle are compact secondary controls with selected states, pressed feedback, and
haptics. Seek and volume use player-specific tracks/thumbs. Codec/file type, bit depth, sample rate,
and bitrate use fixed, artwork-independent muted metadata families selected from their raw values
without changing formatted text. A symmetric left menu button opens the small native Settings/About
menu while the compact right connection pill remains the direct Player devices action. It exposes
only LAN, Wi-Fi Direct, Connecting, or Disconnected; endpoint, API, Server-ID, pairing, re-pair, and
forget details remain on the separate **Player devices** screen. The current data model exposes a
generic saved-device snapshot but intentionally persists only one slot in this release.
All Phone presentation Activities use one edge-to-edge View path and add system-bar plus
display-cutout insets to their root padding. The player keeps required controls in a fixed no-scroll
budget and gives only
the artwork the remaining height; volume remains represented by a disabled placeholder before its
first remote snapshot. The artwork container measures to the smaller available dimension so its
rounded image is always square and `centerCrop` never stretches it. The QR scanner uses its own
non-exported capture Activity: portrait is the default, while a caller already configured in
landscape requests scanner-only landscape. The platform `SeekBar` continues to own touch/accuracy
semantics while its playback track is rendered at `8dp` with a compact `14dp` thumb. Its elapsed
label changes only when the displayed whole second changes; Android 11+ accessibility state reports
the same formatted elapsed/duration values rather than the internal millisecond range.

Behind only this main player content, a custom hardware-accelerated View draws a dark base, a
palette gradient, and three oversized radial-gradient color fields. Their centers move slowly by
canvas transforms; no artwork blur, per-frame bitmap allocation, shader construction, or additional
artwork request is used. Palette changes cross-fade as one visual state. Rapid retargeting starts
from the currently displayed interpolation, while Activity state preserves that visual point over
configuration changes. The motion animator runs only while `MainActivity` is visible. When Android
animations are disabled, both palette transition and perpetual motion are skipped and the final
dark palette is rendered immediately. A constant dark contrast gradient remains above all dynamic
color so existing text, chips, icons, seek, and volume styling stays unchanged.

The square artwork surface contains exactly two reusable `ImageView` layers. A left swipe and Next
move the outgoing layer left while the confirmed incoming artwork enters from the right; a right
swipe and Previous use the opposite direction. Buttons and gestures enter the same request and
transition path. Direct finger movement is governed by a pure touch-slop/direction/commit policy,
with one threshold haptic and at most one command per gesture. Short, vertical, diagonal, cancelled,
multi-touch, or unavailable-control gestures return the artwork to the confirmed center state.

The presentation store keeps a process-local, Server-scoped access-ordered LRU of at most three
service-owned artwork bitmaps. It learns Previous/Next adjacency only from one unambiguous local
command followed by the matching authoritative remote identity; it never infers queue order from
metadata or list position. An exact cached neighbor becomes the second layer during the gesture, so
both covers move as a carousel, but the preview is not promoted until the Server confirms that
identity. Neutral/external changes, rapid ambiguous command sequences, shuffle changes, reconnect,
no-track, command failure, timeout, Server change, or a preview mismatch discard unreliable
adjacency without recycling or copying any bitmap.

The controller's expected interim `onArtworkChanged(null)` is treated as loading rather than absent
artwork, so the outgoing bitmap remains visible. A generation-bound grace timer selects the normal
placeholder only when the current track truly has no artwork or the current load does not complete.
When no exact cached neighbor exists, finger movement uses a bounded rubber-band offset that keeps
most of the current cover visible over the palette-compatible dynamic background; after commit it
holds that small offset until the confirmed incoming bitmap is available. A cached bitmap for a
newly confirmed identity is applied synchronously from `onStateChanged()`, while the later identical
service delivery updates the existing layer without a second transition. Ordinary Activity rebind/
configuration replay likewise paints current presentation state without a false transition. The
live two-layer View never holds more than outgoing and incoming artwork and never calls `recycle()`
on service-owned bitmaps.
Rapid input cancels the single active animator, promotes the newest confirmed layer from its current
visual position, and retargets one latest direction without an unbounded animation/command queue.
If no confirming track/artwork state arrives, a generation-bound 2.2-second presentation timeout
recenters the retained confirmed cover; it never polls or cancels the remote command. Disconnect,
command failure, stop, and rebind likewise settle to confirmed state. With system animations
disabled, commands still run but pending motion and confirmed artwork transitions snap directly to
their final states.

An accepted Play/Pause tap immediately retargets only the inner play triangle/pause bars while the
existing command is sent; a matching newer snapshot confirms that presentation, and mismatch,
failure, timeout, stop, or rebind restores the authoritative state. Confirmed external Play/Pause
and Shuffle changes use the same retargetable morph, with Shuffle moving parallel non-crossing
arrows into crossed arrows. Previous/Next taps nudge their glyphs without moving their buttons.
Like and Dislike pulse once only for visible confirmed transitions to ratings `5` and `1`; initial/
rebound state, duplicate ratings, and removal do not pulse. Main settles all motion Drawables in
`onStop()`, and motion buttons settle on detach. Content/state descriptions and selected tint remain
synchronized with confirmed state rather than animation completion.

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

The maintainer has confirmed basic tracks/Albums browsing, album counts/durations, track-ID play,
and positive/empty search responses on Poweramp `1025004-fa3ec08671d`. The ContentProvider foundation
still needs broader device coverage: first grant/deny/retry and process-not-running behavior;
remaining category projections/order; search by artist/album, Unicode and literal wildcard input;
hierarchy root/children; duplicate playlist/queue entry IDs; queue-current matching and
`OPEN_TO_PLAY`; and album-art access for arbitrary tracks. Category artwork and extra category
metadata are intentionally absent, not failed track metadata. Phone Library/Search integration can
start from this baseline; Queue-specific behavior must be checked before exposing Queue UI.
The 1000-row continuation boundary is an explicit public-contract safety limit, not a claim that
Poweramp libraries are capped at that size.

Exact completed automation, the earlier Server `0.10.2` / Phone `0.5.0` in-place release matrix,
and the maintainer-confirmed Phone `0.6.0` UI validation are recorded in `STATUS.md`. Broader
Android/OEM coverage remains ongoing rather than a blocker for this release.

## Roadmap

Future work is maintained in [`ROADMAP.md`](ROADMAP.md).
