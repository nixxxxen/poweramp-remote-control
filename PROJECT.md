# Poweramp Remote

## Project goal and versions

Poweramp Remote provides a reliable native Android Server for any compatible Android player device
with Poweramp, a retained same-origin Web UI, and a native Android Phone Client.

- Server: `0.11.0` (`versionCode 14`)
- Phone Client: `0.7.0` (`versionCode 16`)
- API: `v1` (unchanged)

The two application versions are deliberately independent. Both old application IDs shipped
`versionCode 7`; the Server counter is now `14` and the Phone counter is `16`. This preserves
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
- `RemoteApiClient` for REST state/control/artwork, paged Library requests, and typed categorized
  Search requests;
- `RemoteWebSocket` for complete event-driven state snapshots;
- `RemoteClientController` for LAN preference, direct fallback, and reconnect coordination;
- `PairingRequestState` for binder-independent QR/manual requests delivered to that controller;
- `PlaybackUiSnapshot`, the service-owned position anchor replayed to a rebound Activity;
- `RemoteSessionPlayer`, a Media3 `SimpleBasePlayer` facade over the remote state and commands;
- one Media3 `MediaSession` exposed to Android System UI, lock screen, and compatible Wear OS
  controllers.

`MainActivity` binds only while visible and remains a playback-only presentation/control surface.
The shared icon-only bottom navigation opens Player / Library / Search / Settings without restarting
the service runtime; its selected/pressed states and English/Russian accessibility labels do not
depend on visible text. The retained entry Activities share one content-transition coordinator:
their fixed bottom navigation and optional mini-player stay outside the translated content view,
Activity window animation is disabled for tab requests, and only the outgoing/incoming content
slides according to the fixed Player / Library / Search / Settings order. A generation gate ignores
repeat and rapid overlapping requests. `LibrarySearchActivity` owns paged Library browsing, typed
categorized Search presentation, and its container back stack; its requests go only through the
bound service/controller. Search headers, per-section **Show more** controls, and local-history rows
are separate row types and never enter artwork or current-track handling. Tracks, Artists, and
Albums continue independently; a section append inserts rows before the following header and owns
its own progress/retry state without resetting the other sections or the live list position.
Opening a Search Artist/Album snapshots the query, typed result, list offset, and current Library-
stack depth, switches the same Activity to Library with the existing content-only transition, and
pushes the normal Library container request. Back trims only that temporary container and restores
the exact Search presentation; existing Library levels/pages stay resident and no Activity or
connection runtime is added.
The Player toolbar also has a 48 dp Queue action in its free left slot, opposite the connection
pill. It opens the separate auxiliary `QueueActivity`, which keeps Player selected in the unchanged
four-item bottom navigation and finishes back to the retained Player Activity. Queue binds to the
same `PhoneConnectionService`, reuses the shared mini-player and Library thumbnail caches, and owns
only its read-only page chain, scroll position, failures, and request generation. It never enters
the Library/Search stack, sorting preferences, or Search history.
Library and Search track rows also consume the service-replayed complete playback snapshot. The
Phone model exposes `underlyingId` only for the API's `track`, `playlist_entry`, and `queue_entry`
wire types; under the current Library contract that value is the row's documented underlying
`folder_files._id`, while container IDs never enter track matching. A static accent indicator is
shown only when the snapshot identity can be matched exactly: ordinary track/Search rows require
`trackRealId == underlyingId`; a Queue entry additionally requires
Queue source category plus exact `trackId == queue._id` and the same underlying ID. Playlist-entry
rows are not guessed because the current playback snapshot has no separately verified playlist
container identity. Metadata, list position, artwork, and optimistic play requests are never used
as substitutes. State-only changes update the visible indicator views without replacing the loaded
row set or restoring its scroll position. An older installed Server that omits the additive identity
fields remains compatible and deliberately produces no current-row indicator.
Loaded Library pages and the current categorized Search result survive same-Server reconnect. Page
append and status rendering leave the live ListView position alone; only navigation between lists
restores a saved offset. Page
failures keep loaded rows visible and stop automatic continuation. A rejected continuation token
offers an explicit list or section reload instead of silently resetting to page one. Library and
Search have no fixed total-row ceiling; every HTTP page remains lazy and bounded to `1…100` rows.
Global Search executes its typed provider selections independently of already loaded Phone/Library
pages.
Track-list Library levels additionally expose two compact 48 dp toolbar actions when the connected
Server advertises sorting: one chooses the criterion and one toggles its human-readable direction.
The Phone keeps one private selection for each bounded logical view type (All tracks, Artist,
Album, Folder, Playlist, and the reserved Other track-container type), never one key per provider
ID. A changed selection invalidates the in-flight generation, clears only that level's page chain,
starts a new first-page request, and moves that list to the top. These preferences survive Activity
recreation, reconnect, and rebind without entering `PairingStore` or Search history. An older Server
without `trackSorting` continues in Poweramp order and shows no sorting actions.
`PlayerDevicesActivity` owns saved-device diagnostics,
QR/manual pairing, re-pair/forget actions, and recoverable permission/settings actions.
`SettingsActivity` links to the presentation-only `AboutActivity`; neither owns or replaces the connection
runtime. No Activity lifecycle cancels P2P negotiation,
removes a group, closes the P2P channel, stops NSD, or closes the API WebSocket. The notification's
explicit Stop action and final service destruction are the teardown paths.

Library, Search, and Settings include one shared fixed mini-player layout directly above bottom
navigation. Its common listener/renderer attaches to the existing `PhoneConnectionService` binder
only for the host Activity lifecycle and consumes the same replayed `RemoteState`,
`PlaybackUiSnapshot` callback stream, and current artwork as the main Player. It creates no network
request, artwork cache, palette analysis, position timer, playback model, MediaSession, or service.
Title, artist, Play/Pause icon, availability, and artwork follow confirmed service state; changing
`trackIdentity()` clears the previous bitmap before the new artwork callback. A retained snapshot
remains visible during temporary disconnect with Play/Pause disabled. No snapshot, Forget, or a
Server change hides it on the next service replay. The body opens the existing Player through the
same task-stack/navigation path, and Settings starts/binds/unbinds the same service without stopping
its runtime.

Library/Search track thumbnails use the same service-owned controller and its dedicated artwork
executor. Only encountered rows are loaded. Decoded bitmaps use a `4 MiB` byte-measured memory LRU;
downsampled JPEG thumbnails use a `32 MiB` private-cache LRU with a `512 KiB` per-entry ceiling.
Keys contain only stable paired Server ID plus the allowlisted track-artwork path, never endpoint or
credential data. One in-flight load is shared for identical keys, row binding and connection
generations reject late display, corrupt/expired disk entries are discarded, and missing/transient
failures have bounded retry delays. Entries refresh after six hours (or immediately when the Server
artwork identity/path changes), survive tab changes and reconnect to the same Server, and are cleared
by Forget device. Disk access, decoding, downsampling, and encoding stay off the UI thread. The
52 dp square list views apply one 8 dp outline clip to both thumbnails and placeholders without
per-bind bitmap processing.
Visible rows retain their displayed bitmap if it is evicted from the shared memory LRU. Binding
tokens include the Activity request lifecycle, so an ignored stop-time callback cannot permanently
block the same row's next load after resume; stale recycled-row deliveries remain rejected.

Artist, album, playlist, and folder rows derive an optional representative cover from the first
usable artwork among at most six tracks returned by that category's existing direct-track route.
Only visible category rows start resolution; folder resolution never descends into child folders.
The lookup reuses an already loaded first container page when available, otherwise requests one
six-item page, and probes artwork sequentially on the same two-thread thumbnail executor. A
service-owned 256-entry LRU maps stable Server ID + category type + category ID to the selected
track-artwork identity; actual image bytes remain solely in the existing `4 MiB`/`32 MiB` cache.
Selected mappings and candidate hints refresh after six hours, changed candidate hints invalidate a
stale selection, missing artwork is retried after ten minutes, and transient failures after 15
seconds. Concurrent resolution for the same category is coalesced, connection/row generations
reject late display, and Forget device clears both the mapping and image caches.

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
shared network disappears. A network is infrastructure LAN only when it has Wi-Fi or Ethernet
transport and does not have `NET_CAPABILITY_WIFI_P2P`; callbacks also reclassify an already known
network through `onCapabilitiesChanged`. Loss of the final infrastructure LAN immediately
invalidates a selected LAN endpoint, closes its WebSocket, restarts NSD, and starts the existing
direct fallback without the normal LAN grace delay. A new DHCP address can replace it automatically.

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
availability restarts ordinary NSD automatically. A P2P network or group existing in Android is not
itself a direct endpoint and cannot change connected transport presentation. `CONNECTED_DIRECT` is
reported only after the identity-checked `WIFI_DIRECT` endpoint has delivered a valid API v1
WebSocket snapshot. Before fresh direct discovery, the existing P2P client inspects and removes an
untracked pre-existing group, rebuilds its channel, and negotiates again; it never trusts that
group's address or bypasses Android/OEM approval. If cleanup cannot be confirmed, direct recovery
stops at the existing user-retry state rather than looping or adopting the group.

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
| `GET` | `/api/v1/library` | Access state, routes, limits, track-sorting, and queue capabilities |
| `GET` | `/api/v1/library/tracks` | Paged All tracks rows |
| `GET` | `/api/v1/library/artists` | Paged Artists rows |
| `GET` | `/api/v1/library/artists/{id}/tracks` | Paged tracks for one artist |
| `GET` | `/api/v1/library/artists/{id}/member-tracks` | Paged tracks related through public `multi_artists` membership |
| `GET` | `/api/v1/library/albums` | Paged Albums rows |
| `GET` | `/api/v1/library/albums/{id}/tracks` | Paged tracks for one album |
| `GET` | `/api/v1/library/folders` | Paged plain-folder rows |
| `GET` | `/api/v1/library/folders/{id}/tracks` | Paged direct tracks in one plain folder |
| `GET` | `/api/v1/library/folder-tree/{id}/folders` | Paged hierarchy children; `id=0` is the root |
| `GET` | `/api/v1/library/folder-tree/{id}/tracks` | Paged direct tracks in a hierarchy folder |
| `GET` | `/api/v1/library/playlists` | Paged Playlists rows |
| `GET` | `/api/v1/library/playlists/{id}/tracks` | Paged playlist entries |
| `GET` | `/api/v1/search?q={query}` | Paged server-side Poweramp track search |
| `GET` | `/api/v1/search/grouped?q={query}` | Independently paged typed Tracks / Artists / Albums search |
| `GET` | `/api/v1/queue` | Paged current queue in provider order |
| `POST` | `/api/v1/queue/add` | Revalidate and append one bounded ordered batch through the public Queue provider API |
| `POST` | `/api/v1/library/play` | Revalidate and enqueue one allowlisted `OPEN_TO_PLAY` target |
| `GET` | `/api/v1/library/artwork/tracks/{id}` | Lazy authenticated JPEG for track artwork |

There is no CORS API, polling endpoint, URL credential, or WebSocket command channel.

### Library, Search, and Queue contract

This is the Server foundation for the Library/Queue series. It adds no Web UI, second service/
Poweramp connection, library WebSocket stream, or persistent copy of Poweramp data. An initial
Library/Search/Queue request reads its provider result through the adapter owned by
`RemotePlaybackService`; Phone still receives only the requested bounded page. All returned cursors
and artwork streams close on success, failure, or cancellation.

The official upstream audit used `maxmpz/powerampapi` master commit
[`60cac5a24348bde03e0619c0ab891bd752750b92`](https://github.com/maxmpz/powerampapi/commit/60cac5a24348bde03e0619c0ab891bd752750b92)
(2026-09-01), including
[`PowerampAPI.java`](https://github.com/maxmpz/powerampapi/blob/60cac5a24348bde03e0619c0ab891bd752750b92/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/PowerampAPI.java),
[`TableDefs.kt`](https://github.com/maxmpz/powerampapi/blob/60cac5a24348bde03e0619c0ab891bd752750b92/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/TableDefs.kt), the Intent API readme, and the official example. The public data authority is
`com.maxmpz.audioplayer.data`. This foundation uses these public paths:

- `files`, `artists`, `artists/{id}/files`, `albums`, and `albums/{id}/files`;
- `folders`, `folders/{id}/files`, `folders_hier/{id}/subfolders`, and
  `folders_hier/{id}/files`, with documented hierarchy root ID `0`;
- `playlists`, `playlists/{id}/files`, and `queue`;
- exact item forms `files/{id}`, `playlists/{playlistId}/files/{entryId}`, and
  `queue/{entryId}` when validating play targets.

For categorized Search, the same public source fixes entity and relation identity as follows:

- a track is `folder_files._id`, an Artist is `artists._id`, and an Album is `albums._id`;
- `TableDefs.MultiArtists` is the always-used one-to-many track-artist relation introduced in
  Poweramp build 899, with `multi_artists.file_id` → `folder_files._id` and
  `multi_artists.artist_id` → `artists._id`; `Artists.IS_UNSPLIT` is the public provider flag for
  a combined multi-artist row, and `folder_files.album_id` links a related file to `albums._id`;
- Artist and Album result rows therefore carry provider IDs directly. Phone never reconstructs a
  container identity from `artist`, `album`, or another display string.

Provider projections are an explicit subset of public `TableDefs` columns:

| Rows | Requested Poweramp columns |
|---|---|
| tracks and search | `folder_files._id`, `folder_files.name`, `title_tag`, `artist`, `album`, `folder_files.duration`, `folder_files.created_at`, `folder_files.played_times` |
| artists | `artists._id`, `artist`, `artists.num_files`, `artists.duration`, `artists.is_unsplit` |
| albums | `albums._id`, `album`, `albums.num_files`, `albums.duration` |
| plain folders | `folders._id`, `folders.name`, `folders.parent_id`, `folders.num_files`, `folders.duration` |
| hierarchy folders | the same identity/name/parent plus `folders.hier_num_files`, `folders.hier_duration` |
| playlists | `playlists._id`, `playlists.playlist`, `playlists.num_files`, `playlists.duration` |
| playlist entries | track columns above plus `playlist_entries._id` |
| queue entries | ID/name/title/artist/album/duration track columns plus `queue._id`; sorting metadata is omitted because Queue always preserves Poweramp order |

Aliases used after the query are Server-local names, not assumed provider columns. Browsing sends
no selection or selection arguments; search uses the fixed parameterized selection described below.
Neither sends a provider sort expression. In particular, browsing does not request private
filesystem paths, queue `sort` as row metadata, or undocumented metadata. The separate documented
Queue append operation queries only raw `MAX(sort)` and inserts only raw `folder_file_id` and
`sort`. The adapter acquires an unstable
`ContentProviderClient` for one attempt and closes the Cursor before the client. Provider death
therefore does not establish a stable dependency that lets Android kill Server with Poweramp;
`DeadObjectException`/`RemoteException` become a controlled unavailable response without retry.

The public list-query parameters are integer `lim` (SQL limit) and integer `shf` (shuffle mode);
the implementation never invents `offset`, keyset, or sort parameters and never requests shuffled
ordering. The audited ContentProvider contract documents no offset/keyset continuation and does not
promise stable ordering across separate queries. It does document each base content URI without
optional query parameters. API `limit` therefore remains a client page size only: it defaults to
`25` and has a hard maximum of `100`.

On an initial request, Server opens the documented base provider URI once, applies the fixed bound
selection when applicable, consumes that one Cursor in its provider order into an immutable
in-memory model snapshot, then closes the Cursor and unstable `ContentProviderClient` before the
HTTP response is returned. A short-lived server-owned paging session serves `1…100`-row slices of
that snapshot. At most eight sessions coexist; they have a five-minute sliding TTL and access-order
LRU eviction. The store retains parsed public model fields only—never a Cursor, provider client,
Android Context, credential, or raw provider URI.

`pageToken` is a 24-character opaque Base64URL capability bound to the exact query/category/
container and one snapshot offset. Retrying a non-final token returns the same slice and next token;
one session therefore has no skips or duplicates even if the live Poweramp library changes. The
last page, expiry, LRU eviction, explicit cancellation discard, API stop, and service shutdown clear
the session; provider failure and request cancellation close the provider resources through the
same request boundary. Invalid, expired, evicted, or cross-query tokens return
`400 invalid_page_token`, which Phone presents as an explicit **Reload** rather than a false end.
Reload starts a new snapshot and is the only way an in-progress traversal observes library edits.
`offset` is only the zero-based index in that immutable Server snapshot; clients cannot submit it
and it is not claimed to be a Poweramp/SQL offset. Library `truncated` remains in API v1 for old
clients but is `false`; actual continuation is represented by `nextPageToken`. Capabilities expose
`maximumContinuationRows: null`, `providerOffsetSupported: false`, and
`continuationModel: "server_snapshot"`.

The sorting field audit uses the same pinned public `TableDefs.kt`. `Files.DURATION` is an
`INTEGER` duration in milliseconds. `Files.CREATED_AT` is the first-seen time in integer epoch
seconds and is therefore the supported date-added source; `Files.FILE_CREATED_AT` is filesystem
mtime in seconds and is deliberately not substituted. `Files.PLAYED_TIMES` is Poweramp's internal
integer play count and is the supported play-count source. The newer
`Files.TOTAL_PLAYED_TIMES` (since build 989) includes plays started while a count-based category or
sort is open, so it has different semantics and is not silently substituted for the stable
Poweramp count. Public `recently_added` and `most_played` category constants were supporting
evidence, not a replacement for per-row numeric fields.

The actual public cursor was also queried read-only on installed Poweramp build `1025004`
(`build-1025-bundle-play`). `_id`, `title_tag`, `name`, `artist`, `album`, `duration`, `created_at`,
`played_times`, and `total_played_times` all resolved as numeric/text columns on `/files`; the same
selected `created_at` and `played_times` columns resolved for Artist, Album, hierarchy-Folder, and
Playlist track cursors. `recently_added` exposed `created_at`, and `most_played` exposed the
descending `played_times` values. The public declarations do not promise non-null row values, so
Server retains exact non-negative integers when present and emits `null` for absent/null/invalid
values; Phone never derives either value from an ID or loaded-page position.

Library track-list routes additively accept a paired selection:
`sort=default|title|album|artist|duration|date_added|play_count` and
`direction=asc|desc`. Omitting both keeps the old request and provider order byte-for-byte; sending
only one, an unknown value, or sending sorting to a container list, Search, or Queue is rejected.
`GET /api/v1/library` advertises only `default` unless the provider probe is currently available;
an available verified provider advertises the complete criteria and both directions. Thus an old
Server or unavailable provider cannot cause Phone to present an unsupported choice.

For a non-default sort the Server first consumes the complete provider Cursor into the immutable
model snapshot described above, then sorts that full snapshot, and only then creates bounded HTTP
pages. Missing text/numeric values stay at the end in both directions. Text keys use
`Locale.ROOT` case folding, Unicode decomposition with combining-mark removal, trimmed/collapsed
whitespace, then deterministic original-text comparison. Equal primary values use fixed title,
album, artist, duration, date, and play-count comparisons followed by the unique playlist-entry ID
where applicable and final `folder_files._id`. The paging identity includes route category,
container ID/path, any existing filter, criterion, and direction; a token from another selection is
invalid, so pages cannot be mixed or independently sorted.

Playlist sorting changes only the immutable display snapshot. Each row retains its public
`playlist_entries._id`, and tapping it still sends the exact documented
`/playlists/{playlistId}/files/{entryId}` target; Poweramp therefore starts that selected occurrence
and owns subsequent playback in the saved playlist order. Global Search relevance/grouping and
Queue provider order are unchanged.

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
`durationMilliseconds`, `dateAddedEpochSeconds`, `playCount`, `trackCount`, `artwork`, `play`, and
`current`. `id` is the underlying
Poweramp track/category ID. Playlist and queue rows additionally preserve their distinct public
`playlist_entries._id` or `queue._id` as `entryId`; duplicate uses of one track therefore stay
distinct. `parentId` identifies the requested containing category for track/playlist-entry rows and
the documented parent folder for folder rows; a folder parent may be root `0`. Duration comes only
from documented millisecond columns; container counts only from `num_files`/`hier_num_files`.
Date added and play count preserve the verified raw numeric track fields and appear in localized
Phone track-row details. Missing, null, invalid, or unexpectedly
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
are its only parameters. Its initial snapshot queries the documented `/files` base URI using the
normal track projection and fixed
`LIKE ? ESCAPE '!'` selection over `title_tag`, `folder_files.name`, `artist`, and `album`.
The contains-pattern is supplied only through four bound `selectionArgs`; `%`, `_`, and `!` in
user input are escaped as literals. Matching executes in Poweramp before Server snapshot paging,
not over a locally downloaded Phone page. Results are tracks, not separate artist/album entities;
ordering and case/diacritic matching remain provider-defined. The historical route now uses the
same unlimited snapshot continuation model while retaining its response fields and API v1 path.

The additive Bearer-only `/api/v1/search/grouped` route leaves that historical response untouched.
It accepts required `q` and optional `limit` (`1…100`, default `25`). Its initial response keeps
the old non-empty Tracks → Artists → Albums sections and `truncated` fields, and additively supplies
an independent `nextPageToken` on every section that has more matches. A continuation requires both
`section=tracks|artists|albums` and that section's query-bound `pageToken`; it returns only the
requested section's next slice. Normal completion has no token and is not truncation. An expired
token is recoverable by reloading only that section from a new snapshot. Other sections and Phone
scroll state remain untouched.

Separate fixed selections run inside Poweramp before Server ranking: `title_tag LIKE ? ESCAPE '!'`
for track titles, `artist LIKE ?` for direct Artist names, and `album LIKE ?` for Album names.
Literal LIKE metacharacters are escaped and all user/relation IDs are bound through
`selectionArgs`. Every provider Cursor is consumed to its actual end; neither grouped candidates
nor final section continuation has a fixed total-row ceiling.

Server builds a comparison-only key with trim, `Locale.ROOT` case folding, Unicode decomposition
and combining-mark removal, dash canonicalization, standalone `and`/`&` equivalence, and collapsed
whitespace. Original provider text is never changed. Exact, prefix, substring/token, then bounded
Damerau-Levenshtein ranks are deterministic; typo distance is disabled below five code points, is
one for lengths 5–7, and is two from length 8, with at most five fuzzy results. Fuzzy rows are used
only when that entity section has no deterministic or relation-backed result. A small bounded set
of normalized and unchanged-substring provider probes handles provider-side accent and punctuation
differences. Punctuation is preserved in the strong key; a punctuation-insensitive token key is
only a weaker match and no alphabet transliteration is performed.

Artist/Album fuzzy fallback uses one service-owned lazy in-memory index of normalized public rows.
The single background worker builds it only after provider `LIKE` and normalized probes find no
stronger candidate; ordinary deterministic searches do not wait for it. Concurrent/repeated fuzzy
queries share the same index, which has a two-minute TTL and generation, is never persisted or sent
to Phone, and is cancelled/cleared on service shutdown. With no reliable public library-change
event in this integration, the short TTL is the documented invalidation policy. Request
cancellation remains responsive while an already-started service-owned build may finish for a
later query; provider failure remains a sanitized recoverable error.

Tracks remain title-only and never gain Artist tracks. All exact matches are ranked first, followed
by prefix and substring/token matches; the presence of an exact title never suppresses a weaker
matching Track. Track fuzzy fallback remains disabled, and all Track rows are deduplicated only by
their public `folder_files._id`. Direct Artist exact matches
suppress their partial matches; otherwise provider rows marked `artists.is_unsplit=1` are excluded,
while an exact full composite name remains eligible. Exact-track related Artists are added through
`multi_artists` and all Artist rows are deduplicated by `artists._id`. Every grouped Artist has an
additive `browse:{type:"artist_membership",id}` target and opens the new
`/api/v1/library/artists/{id}/member-tracks` route. That route queries outer `/files` rows with an
`EXISTS` membership selection, so each related `folder_files._id` appears once while the historical
`/artists/{id}/tracks` route remains unchanged. Representative artwork uses the same membership
route and therefore the same file set. Grouped Artist `trackCount` comes directly from public
`artists.num_files`; after Poweramp was configured to split participant relations, device checking
confirmed that aggregate agrees with the unique membership set, including solo and collaboration
tracks. It is never inferred from loaded pages; a missing/invalid public count stays `null`.

Albums are the `albums._id` union of direct title matches and albums reached from displayed Artist
IDs through `multi_artists.file_id` plus `folder_files.album_id`. Exact direct album titles come
first, then relation-backed Albums, then remaining prefix/substring direct matches; fuzzy Albums
are a last fallback only when neither deterministic nor relation matches exist. Each section is
capped only per HTTP page by `limit`, with independent continuation to the actual end. The response
still omits empty sections and serializes non-empty sections only as Tracks → Artists → Albums.

A query containing an explicit spaced ASCII or typographic dash is additionally interpreted as
`artist - title`. The left side resolves through the same normalized/fuzzy Artist policy; the right
side is matched independently against Track and Album titles. Bound `multi_artists`/
`folder_files.album_id` relations restrict both sections to the resolved Artist IDs, while that
Artist remains in Artists. Hyphens without surrounding spaces, such as `Sān-Z`, never split. If the
left side resolves to no Artist, Server runs the ordinary full original query instead. Phone never
parses display metadata.

Phone parses the optional browse and continuation fields (their absence from an older Server
remains valid), retains its existing query/connection generation gate, and preserves Search
origin/scroll while opening the existing Library surface. A 48 dp clear button is visible only for
non-empty input; it clears debounce, the active request generation, results, and every section
continuation while keeping focus and IME. Private Phone-only Search history contains at most 20
completed user queries, newest first, with stable Search-normalized deduplication but original
display text. It records only explicit IME Search/Enter, a selected Search result, or IME close
after the current full query has succeeded—never live debounce fragments or failed/empty queries.
History appears only for focused empty Search input while
`WindowInsetsCompat.Type.ime()` is actually visible. Each entry has its own 48 dp remove action;
selecting an entry starts Search, while removal does not. The separate `search_history`
SharedPreferences contains no endpoint, Server ID, credential, or result and is unaffected by
Forget/Re-pair.

The route uses the same service-owned provider adapter, request cancellation, authentication, and
sanitized failures; it introduces no WebSocket, persistent index, service, or Poweramp path.
`/search?flt` remains forbidden.

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

Queue Cursor order is retained without an invented sort expression. Every wire row keeps
`id = folder_files._id` separately from `entryId = queue._id`; Queue pages and Phone presentation
never deduplicate on the underlying ID. When the playback snapshot reports category `QUEUE`, Phone
marks a row only when both `trackId == entryId` and `trackRealId == id`. It deliberately ignores the
page's older nullable `current` field as a live source of truth. An older Server that omits the
additive playback identity fields still supplies a usable list and play targets, but Phone shows no
guessed current indicator.

This identity was confirmed read-only on installed Poweramp build `1025004`
(`build-1025-bundle-play`) after preparing four entries through the official public Queue-insert
sample path. Provider/API order was underlying IDs `6890, 6909, 6890, 6914` with distinct positive
Queue entry IDs `1, 2, 3, 4`. Selecting entry `3` through `/api/v1/library/play` produced playback
category `800`, `trackId=3`, and `trackRealId=6890`; selecting the other duplicate produced
`trackId=1` with the same real ID. With shuffle off, Next from entry `3` advanced to entry `4`, so
Poweramp—not Phone—continued the real Queue order. A `limit=2` traversal returned offsets `0` and
`2` with all four occurrences exactly once. With provider access available, capabilities are:

```json
{
  "read": true,
  "playExisting": true,
  "add": true,
  "remove": false,
  "reorder": false,
  "playNext": false
}
```

`POST /api/v1/queue/add` implements the official Add-to-Queue sample and accepts one `items` array
of 1…100 structured `track`, `playlist_entry`, or `queue_entry` targets. IDs must be positive
integers; unknown, missing, and extra fields, client URIs, raw provider values, and empty/oversized
batches are rejected. Server requeries every exact public target first and resolves its underlying
`folder_files._id`; no insert begins unless the complete batch validates. Duplicate tracks and
distinct playlist/Queue occurrences are deliberately retained in array order.

The existing service-owned Library worker serializes the complete `MAX(sort)` → insert sequence so
concurrent requests cannot allocate the same next position. On Poweramp build 1025 the qualified
projection `MAX(queue.sort)` unexpectedly returns `NULL` even when row `sort` values are present,
while the official raw projection `MAX(sort)` returns the correct maximum; Server therefore uses
the device-confirmed raw field name. Each successful insert contains exactly `folder_file_id` and
the next sequential `sort`. After at least one success Server sends exactly one explicit
`ACTION_RELOAD_DATA` broadcast with its package and table `queue`, then closes every Cursor/client/
editor on success, cancellation, provider failure, and service shutdown. It never opens Poweramp
UI.

Provider batches are not transactional. The response always states `requestedCount`, `addedCount`,
`complete`, and the safe first failure index/status when applicable; Phone never automatically
retries a mutation. Any successful prefix invalidates only Server Queue paging snapshots. Remove,
Clear, Reorder, and Play Next remain absent because the audited public repository documents no
equivalent contract. MediaSession queue mutations, internal database access, hidden intents, and
UI automation are not used by the product.

Phone Queue requests use `/api/v1/queue?limit=25` and the existing opaque page token through
`PhoneConnectionService`/`RemoteClientController`. Reload invalidates the active request generation,
starts a new Server snapshot at page one, and moves the list to the top. Continuation failures never
retry automatically; loaded rows remain visible during a temporary disconnect or recoverable
failure, and same-Server reconnect/rebind retains them. A different Server clears the page chain.
Tapping an entry sends only its Server-supplied structured `queue_entry` target and creates no
optimistic current/reorder/removal state; the existing playback WebSocket snapshot confirms the
selection. Capability-gated `⋮` actions on track, playlist-entry, and Queue-entry rows expose only
**Add to Queue**. Long press starts an ordered maximum-100 selection over already loaded track rows
in Library, Global Search, or Queue; exact playlist/Queue entry identity keeps duplicate
occurrences separate. Full success clears selection, full failure retains it, and a partial result
removes only the successfully inserted prefix while reporting `X of Y`. The UI blocks concurrent
submission and never appends rows optimistically. The existing Phone service retains one bounded
operation identity and its last completion so an Activity recreation cannot resend or lose an
in-flight batch; it does not turn an ambiguous network failure into a retry. A successful append
marks Queue content dirty; an active or retained Queue screen discards its old tokens and reloads
page one.

Queue rows reuse rounded artwork, title/artist/album/duration typography, the shared
mini-player, safe insets, and localized loading/empty/permission/provider/retry presentation. Queue
has no sorting, removal, clearing, reorder, or Play Next controls. Servers that omit
`queueCapabilities.add` retain all read/play behavior and show no add controls.

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
Malformed Queue append JSON is `400 invalid_queue_add_request`. A valid append returns its bounded
result body even for a controlled `403`, `404`, or `503`; internal URIs, exception messages, and
private metadata are never returned.

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
- `trackId` is the positive raw Poweramp `TrackInfo.id`, including a playlist/Queue entry identity
  when that is the active source; non-positive or unavailable values are `null`;
- `trackRealId` is the positive underlying Poweramp `TrackInfo.realId` corresponding to the
  Library `folder_files._id`; non-positive or unavailable values are `null`;
- both identity fields are additive and nullable: an older API v1 Server may omit them, and Phone
  then keeps playback presentation but shows no Library/Search current-row indicator;
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
without changing formatted text. The compact right connection pill remains in its original top-right
position as the direct Player devices action; the former left menu is replaced by the shared bottom
navigation, and About is reached through Settings. The pill exposes
only LAN, Wi-Fi Direct, Connecting, or Disconnected; endpoint, API, Server-ID, pairing, re-pair, and
forget details remain on the separate **Player devices** screen. The current data model exposes a
generic saved-device snapshot but intentionally persists only one slot in this release.
All Phone presentation Activities use one edge-to-edge View path and add system-bar plus
display-cutout insets to their root padding. Library/Search additionally relies on resize handling
for the software keyboard, without applying a second inset layer. The player keeps required controls in a fixed no-scroll
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

The exact Poweramp `bitRate` unit and the `posInList` index base outside Queue still require device
verification; Queue is confirmed zero-based on Poweramp build 1025. API v1 intentionally preserves
the raw values. Phone presentation alone maps a valid Queue `0…N-1` position to `1…N`; null,
negative, out-of-range, incomplete, and non-Queue values keep the safe existing fallback, and the
next non-Queue snapshot removes the Queue-specific conversion. Wi-Fi Direct behavior also varies by
vendor: the Server must
be selected as group owner for the current IPv4 client path, system approval may be required after
prior pairing, and dual LAN/P2P routing must be checked on representative Android 8–16 devices.

The maintainer has confirmed basic tracks/Albums browsing, album counts/durations, track-ID play,
and positive/empty legacy search responses on Poweramp `1025004-fa3ec08671d`. Poweramp is now
configured to split Artists on `,` and `;`, and a matching debug Server/Phone pair has exercised the
published `artists.is_unsplit`, `artists.num_files`, `multi_artists` membership, and related-Album
relations. The maintainer confirmed the completed Global Search/Library matrix: exact plus partial
Track ordering (including `Beyond Oblivion`), canonical Artist totals and solo/collaboration browse,
large lists beyond the former 1000-row boundary, independently continued Search sections,
structured queries, clear/history/IME behavior, repeated fuzzy speedup, Back/query/scroll/insets,
and unchanged playback UI. Server uses only those provider relations and never parses Artist display
strings.

The maintainer subsequently confirmed the complete Library sorting matrix on the matching Server
and Phone debug builds: every advertised criterion/direction, raw date/play-count presentation,
continuation beyond 1000, Artist/Album/Folder/Playlist scopes, switching during continuation,
Back/recreation/reconnect/rebind persistence, exact Playlist-entry launch with Poweramp follow-on
order, and unchanged Global Search all behave as specified.

The ContentProvider foundation still needs broader device/OEM coverage: first grant/deny/retry and
process-not-running behavior; remaining category projections/order; hierarchy root/children;
duplicate playlist entry IDs; album-art access for arbitrary tracks; and derived representative
covers across artists, albums, playlists, and direct folder tracks. Queue provider order, duplicate
entry IDs, exact current matching, `OPEN_TO_PLAY`, empty/multi-page presentation, and the complete
Phone Stage 1 matrix are confirmed on the current device setup. Extra category metadata remains
intentionally absent, not failed track metadata.
Live library or Queue edits do not mutate an existing paging snapshot: users must Reload to start a
fresh view. A successful mutation made by this Phone is the narrow exception: it marks only Queue
dirty and starts a fresh Queue snapshot without mixing continuation tokens. On the tested Poweramp
build the Queue playback snapshot exposes zero-based `posInList`; Phone adds one only in Queue
presentation, while Server/API state remains raw and the index base for other source categories
remains unverified.

Exact completed automation, the earlier Server `0.10.2` / Phone `0.5.0` in-place release matrix,
and the maintainer-confirmed Phone `0.6.0` UI validation are recorded in `STATUS.md`. Broader
Android/OEM coverage remains ongoing rather than a blocker for this release.

## Roadmap

Future work is maintained in [`ROADMAP.md`](ROADMAP.md).
