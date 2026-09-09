# Poweramp Remote Roadmap

Current release versions are Server `0.10.2` and Phone Client `0.6.0`; API remains backward-
compatible `v1`. Server is unchanged in the Phone UI release.

Confirmed implementation and verification are tracked in `STATUS.md`. Server and Phone Client use independent application versions; API compatibility is tracked separately.

## 0.10.2 Server / 0.5.0 Phone — localization and minimal navigation

Implemented without a new service, connection runtime, transport, or API version:

- Phone adds a symmetric main-menu button and a small native Settings/About menu while retaining
  the direct Player devices button and compact non-scrolling player layout;
- Settings owns only presentation language selection: stable private `system`/`ru`/`en` tags,
  Android 13+ `LocaleManager`, API 26–32 configuration-context fallback, immediate Activity
  recreation/resume handling, and notification/channel refresh in the existing service;
- About reads package version metadata through `PackageManager` and provides repository,
  MIT-license, third-party-notice, and independence information with ordinary `ACTION_VIEW` links;
- Phone has complete English fallback and Russian resources across every Activity, scanner prompt,
  dialog, Toast, status/error, accessibility description, foreground notification, channel, and
  runtime metadata formatter;
- Server Android UI, foreground notification/channel, and dependency-free Web UI are English-only,
  including HTML language, JavaScript messages, and ARIA labels;
- raw bitrate/list values, historical API v1 payloads, application IDs, pairing preferences,
  credentials, discovery, LAN preference, Wi-Fi Direct fallback, MediaSession/Wear behavior, and
  both existing services remain unchanged.

The signed release pipeline and complete in-place real-device matrix passed with the exact final
APKs; results are tracked in `STATUS.md` and `RELEASING.md`.

## Phone 0.6.0 — dynamic artwork theme

Released without changing Server `0.10.2` or API `v1`:

- the existing View-based main player derives one deterministic two/three-color dark palette from
  its already loaded artwork, with normalization and a calm fallback for absent, failed, neutral,
  or insufficiently diverse images;
- analysis runs off the main thread and is coalesced through a 12-entry color-only LRU keyed by
  paired Server identity plus the existing stable artwork key; Activity rebind, revision updates,
  reconnect, and transient endpoint changes do not repeat it;
- only the main player receives a dark palette gradient and three large soft radial-gradient fields;
  their slow lifecycle-bound movement uses canvas transforms and prebuilt shaders, not bitmap blur;
- palette transitions reject stale generations and retarget from the current visual interpolation,
  including rapid track changes and configuration restoration; disabled system animations render
  the final palette immediately and do not start perpetual movement;
- existing player controls, chip/icon colors, non-scrolling geometry, safe insets, square artwork,
  visible volume, service-owned playback state, MediaSession, transport, Server, and Web UI remain
  unchanged.

Control morphs/pulses and smooth seek presentation are implemented as the third stage below. The
compact connection indicator and fixed, artwork-independent metadata-chip families are implemented
as the fourth stage below; artwork-dependent control/chip colors remain separate future work.

## Phone 0.6.0 — artwork swipe and unified track transition

Released as the second Phone `0.6.0` UI package with Server `0.10.2` and API `v1` unchanged:

- horizontal gestures are recognized only inside the existing square artwork container; left maps
  to Next and right to Previous after a centralized touch-slop, direction-dominance, and commit
  threshold policy, with one threshold haptic and one command at most per gesture;
- swipe and the existing Previous/Next buttons enter one navigation coordinator and the same
  two-layer artwork transition: Next exits left/enters right, Previous exits right/enters left, and
  external confirmed track changes use a neutral cross-fade without guessing direction;
- remote snapshots remain authoritative. User input starts only presentation motion and sends the
  unchanged command; metadata, track identity, and incoming artwork change only after existing
  service/WebSocket confirmation;
- the expected intermediate artwork `null` keeps the outgoing cover. Current track/artwork
  generations reject late results, and a bounded grace period changes to the regular placeholder
  only for genuinely missing, failed, or undecodable current artwork;
- a Server-scoped process LRU retains at most three already displayed service-owned bitmaps and
  learns directional neighbors only from an unambiguous local Previous/Next followed by the exact
  confirmed remote content identity; no queue/list-position inference or artwork prefetch exists;
- an exact cached neighbor is visible beside the current cover during the swipe and both layers move
  as one carousel. With no exact neighbor, the current cover uses a bounded rubber-band/pending
  offset over the dark dynamic background instead of exposing an empty gray card;
- after a matching snapshot, cached artwork enters the existing transition immediately; the later
  identical service bitmap only updates that layer. A mismatched preview, neutral/external change,
  rapid ambiguous input, shuffle change, reconnect, no-track, failure, timeout, or Server change
  removes unreliable adjacency;
- rapid inputs each send one command but collapse to one latest visual intent and one active
  animator. Retargeting promotes the latest confirmed layer from its current position, while
  a generation-bound confirmation timeout, command error, disconnect, Activity stop/rebind, and
  recreation settle safely on confirmed state without polling or cancelling remote commands;
- the service replay and bounded presentation store prevent false placeholder/track transitions on
  Activity rebind. The live artwork surface still holds only outgoing and incoming layers, does no
  bitmap processing per frame, and leaves palette analysis in the existing cache/generation path;
- disabled system animations preserve commands and immediately apply the final confirmed artwork.
  Square geometry, crop/rounding, safe insets, the non-scrolling compact layout, visible volume,
  all controls/accessibility state, both services, MediaSession/Wear, transports, Server/Web UI,
  and API `v1` are unchanged.

## Phone 0.6.0 — control motion and smooth playback progress

Released as the third Phone `0.6.0` UI package, with Server `0.10.2` / code 13, Phone `0.6.0` /
code 15, and API `v1`:

- an accepted Play/Pause tap immediately retargets one internal glyph while the unchanged command
  is sent; matching confirmation keeps it, while mismatch/failure/timeout returns to authoritative
  state without moving the primary button, background, padding, or touch target;
- Previous/Next taps nudge only their glyphs in the requested direction, and animated playback
  buttons use rounded pressed surfaces rather than a square foreground ripple;
- confirmed Shuffle state morphs between parallel non-crossing OFF arrows and crossed ON arrows,
  while its existing selected tint and binary command semantics remain unchanged;
- Like and Dislike pulse only on visible confirmed transitions to ratings `5` and `1`; initial
  replay, duplicate active ratings, removal, and the rating dialog retain their existing behavior;
- pure confirmed-state policies make duplicate renders idempotent and rapid binary reversals retarget
  one Drawable animator from current progress. Activity replay, stop/detach, and disabled animator
  scale settle immediately without an animator queue;
- the existing platform playback SeekBar uses millisecond presentation progress sampled locally from
  the unchanged service-owned monotonic `PlaybackUiSnapshot`; no network polling, callback-time
  re-anchoring, second service model, or `RemoteSessionPlayer` change is introduced;
- a pure reconciliation policy eases only small same-track playing discrepancies over a bounded
  280 ms interval. Large discontinuities, track change, pause/resume, reconnect, and failed/timed-out
  seek return directly to authoritative state;
- manual drag blocks automatic thumb updates and retains one integer-second existing seek command,
  one completion haptic, and the bounded pending-seek protection against stale snapshots;
- a reusable lifecycle-bound frame callback runs only while Main is visible, connected, playing,
  and not dragging. Elapsed text updates only at whole-second boundaries; animator scale `0` keeps
  position accurate using a conservative 250 ms local cadence;
- artwork theme/swipe/cache/transitions, square/non-scrolling layout, safe insets, visible volume,
  metadata/chip/control colors, both services, MediaSession/Wear, transports, Server/Web UI, and API
  `v1` remain unchanged.

## Phone 0.6.0 — connection indicator and metadata chip styles

Released as the fourth and final planned Phone `0.6.0` UI package. Server `0.10.2` / code 13 and API
`v1` remain unchanged; Phone advances to code 15:

- the direct top-right Player devices action is now a compact, one-line pill driven only by the
  existing controller callbacks and `PlayerDeviceSnapshot`: LAN and Wi-Fi Direct have distinct
  transport labels, active discovery/connection/retry states collapse to Connecting, and required-
  action/auth/unsupported/error states collapse to Disconnected;
- the pill exposes no endpoint, API version, Server ID, or other diagnostic text; those details and
  all pairing/recovery actions remain in `PlayerDevicesActivity`;
- a pure metadata policy normalizes codec/container input with `Locale.ROOT` and assigns stable muted
  variants for common codecs, 16/24/32-bit depth, standard/high sample rates, and safe fallbacks;
- bitrate retains one muted amber/neutral style and its existing tolerant value formatter, avoiding
  an unverified quality classification;
- an Activity-owned, enum-bounded drawable cache supplies one dark translucent surface, fixed
  borders, and readable accent text without retaining a static Context or creating Drawables on
  duplicate snapshots;
- chip styles do not consume artwork palette state, and no animation runs for position-only or
  repeated metadata updates. Layout dimensions, horizontal chip scrolling, artwork and control
  motion, services, transport, MediaSession/Wear, Server/Web UI, and API `v1` remain unchanged.

## 0.10.1 Server / 0.4.2 Phone — QR and playback/UI regression fixes

Implemented without new product scope or an API version change:

- Server removes the Android-incompatible static regexp that crashed `PairingRequest.<clinit>` and
  parses `/api/v1/pair` with `org.json`, strict raw-type/version/canonical-ID/secret validation,
  field-order independence, unknown-field tolerance, sanitized controlled failures, and
  request-boundary diagnostics;
- Phone keeps scanner handoff binder-independent in the existing `PhoneConnectionService`, assigns
  a lifecycle-stable UUID to each scanner launch, suppresses/replays duplicate delivery without a
  second one-time exchange, persists QR credentials before connecting, and reloads the same
  identity for normal restart discovery;
- Phone timestamps each WebSocket state at socket receipt and shares one playback anchor between
  `PhoneConnectionService`, the player UI, and MediaSession, removing main-looper delivery lag;
- a scanner-only Activity defaults to portrait and follows an explicitly landscape calling screen
  without locking the rest of Phone Client;
- the flexible rounded artwork container always measures to a 1:1 square.

## 0.4.1 Phone — regression fixes

Implemented without a Server or API version change:

- QR scanner results enter the existing `PhoneConnectionService` through a private service-owned
  request handoff, eliminating the Activity rebind race on cold launch and scanner return;
- **Enter token manually** restores the LAN-discovered persistent Bearer-token fallback alongside
  **Scan QR code**, without adding address entry or another connection architecture;
- both Phone screens explicitly handle system bars, display cutouts, and navigation/gesture insets;
- the playback track is thicker, the compact no-scroll layout reserves space for volume, and
  artwork flexes with available height instead of forcing controls below the viewport;
- service-request and repeated playback-rebind contracts have JVM regression coverage.

## 0.10.0 Server / 0.4.0 Phone — QR pairing and Phone surfaces

Implemented:

- Server renders an address-free QR containing the stable public Server ID, API version, readable
  device name, and a random two-minute one-time secret. It contains no persistent credential or IP.
- Phone **Pair new player** and **Re-pair** scan that QR, search only the scanned identity through
  existing LAN-first NSD and Wi-Fi Direct fallback, then atomically exchange the secret for the
  unchanged persistent API v1 Bearer credential.
- A separate generic **Player devices** screen presents the saved device name, connected state,
  current LAN/Wi-Fi Direct transport, diagnostics, re-pair, and forget actions. Persistence remains
  one-slot in this release; a full multi-player collection/selector is still future work.
- `PhoneConnectionService` now replays an extrapolated service-owned playback snapshot whenever the
  player Activity rebinds. The seekbar no longer restarts from zero after resume, and no polling was
  introduced.
- The Phone main screen is playback-only and non-scrolling on a typical smartphone, with larger
  rounded artwork, compact metadata chips and secondary controls, player-styled seek/volume,
  selected states, ripple feedback, and haptics.
- Existing REST/WebSocket routes, protected-route Bearer auth, browser sessions, LAN/P2P transport,
  MediaSession/Wear controls, and embedded Web UI remain compatible. The additive
  `POST /api/v1/pair` route is used only for the one-time credential exchange.

The mandatory first-public-release fresh-install matrix passed on the Android 12 Server and Android
16 Phone devices recorded in `STATUS.md`. Further post-release/OEM validation should continue to
cover scanner camera variants, QR expiry/reuse, LAN and pre-association P2P initial pairing,
Activity recreation, short-screen/accessibility behavior, and adverse permission/network states.

## 0.9.0 Server / 0.3.0 Phone — Remote system integration

Implemented:

- Server exposes the player device's Android media-stream current/max volume and accepts `set_volume`. The official Poweramp Intent API exposes no public volume command, so system `STREAM_MUSIC` is used instead of private Poweramp APIs.
- Web UI and Phone Client provide WebSocket-synchronized remote volume controls.
- Phone Client publishes a Media3 `MediaSession` backed by a custom remote `SimpleBasePlayer`. It has no local audio output, ExoPlayer, or audio-focus ownership.
- Android notification/lock-screen controls and compatible Wear OS controllers expose metadata, artwork, position, previous, play/pause, next, and seek.
- LAN → Wi-Fi Direct recovery reinitializes stale endpoints and P2P discovery without requiring a Server restart.

Verified on the current real-device setup:

- remote volume control, including player-side volume changes;
- MediaSession notification controls;
- Wear OS playback control;
- LAN → automatic Wi-Fi Direct fallback;
- background/screen-off Server and Phone operation.

Further validation should focus on other Android vendors/versions, fixed-volume devices, network edge cases, and long-running stability.

## 0.8.x Server / 0.2.x Phone — Connection hardening

Implemented in Server `0.8.1` / Phone Client `0.2.1`:

- LAN/NSD remains the preferred transport.
- Wi-Fi Direct is used automatically as fallback when a paired Server is not reachable through LAN.
- Server independently runs peer/service discovery without requiring Android Settings to be open.
- Phone P2P discovery, group monitoring, WebSocket, reconnect, and network recovery run from a `connectedDevice` foreground service rather than Activity lifecycle.
- Existing P2P groups can reconnect after temporary loss.
- No wakelock, Wi-Fi lock, or keep-screen-on is required on the verified target setup.

Remaining hardening:

- cover Android 8–12L location requirements and Android 13+ Nearby devices permission across more devices;
- handle vendor-specific group-owner selection and unusual Wi-Fi Direct implementations;
- improve diagnostics without exposing credentials or persisting transient addresses;
- evaluate Local Only Hotspot only as a fallback if Wi-Fi Direct proves unreliable on specific devices.

## Current — Library browsing and search

Treat Server/Poweramp as the source of truth. Do **not** replicate the entire Poweramp database to Phone.

Expose a paged/lazy library API backed by the documented Poweramp ContentProvider.

Initial browsing structure:

- All tracks;
- Artists;
- Albums;
- Folders and folder hierarchy;
- Playlists;
- Search.

Server foundation is implemented at the unchanged Server `0.10.2` / API `v1`: bounded
ContentProvider-backed routes, strict ID/category/query validation, lazy track artwork, explicit
permission state/action, and structured `OPEN_TO_PLAY` targets are implemented. A first Phone
Library/Search UI candidate now adds the agreed navigation, bounded paging, lazy thumbnail cache,
container Back stack, and stale-search protection at unchanged Phone `0.6.0`. Versions increase only
after the whole series is complete. Basic Server tracks/Albums browsing, track-ID play, and
positive/empty search are confirmed on the maintainer's Poweramp build; the new Phone surface and
remaining category/permission/large-library matrix still require device validation.

Phone navigation is now Player / Library / Search / Settings in a bottom bar. About is in Settings,
the top-left main menu is removed, and the existing connection pill is retained. A later Queue task
can add its Player entry after the Queue device matrix. Navigation preserves playback and the
existing service-owned runtime.

Load only the currently requested category/page and cache recent results locally for responsive navigation.

Long-list Phone stability is implemented pending real-device confirmation: append/status updates
do not restore stale offsets, same-Server reconnect keeps loaded pages, continuation failures keep
the list visible without automatic retries, and visible artwork survives RAM eviction/rebind.
The current Server browse window still stops at 1000 provider rows. Before removing this limit,
verify a supported bounded continuation/ordering strategy against the public provider and a large
real-device library, including live library changes. Do not merely raise the prefix-scan cap or
invent an undocumented SQL offset. Scoped search and sorting below must operate before pagination,
not only on this already loaded window; existing global Search remains the current workaround.

Search executes in Poweramp through `/files` with a fixed parameterized title/file-name/artist/album
selection, verified with matching and nonmatching queries on Poweramp `1025004-fa3ec08671d`.
The obsolete `/search?flt` crashes that build and is excluded without fallback. The historical
route still returns track rows only; the completed global-search stage adds a separate typed
Tracks / Artists / Albums route after verifying public provider IDs and the `multi_artists`
relation. It neither copies the database nor filters an already downloaded Phone page. Playlist
search remains separate future scope until its contract is requested and verified.

Support playback actions for documented Poweramp content URIs:

- play selected track;
- start an album from a selected track;
- start a playlist;
- start another supported category/list.

Artwork should be loaded lazily rather than transferred for the whole library.

### Library/Search UI refinements (requested 2026-09-07)

These follow-up requirements are implemented incrementally while retaining the existing
service-owned playback/connection runtime, safe insets, and the non-scrolling Player with visible
volume. Items 1–6 and the currently applicable Library/Search portion of 8 are complete at
unchanged versions. The current-row indicator and content-only tab transitions were confirmed on
matching-revision Server and Phone debug builds on 2026-09-08.
Versions advance when the series is ready for release, not for each refinement.

1. **Completed — icon-only bottom navigation.** Player, Library, Search, and Settings now use
   recognizable vectors without visible labels, with selected/pressed states, 48 dp minimum touch
   targets, and localized accessibility labels. Launcher icons are not part of this change.
2. **Completed — reliable track-thumbnail caching.** Encountered rows use a `4 MiB` decoded-memory
   LRU plus a `32 MiB` private encoded-disk LRU with `512 KiB` per-entry ceiling. Stable Server ID
   and allowlisted track-artwork path form the key; endpoint and credentials do not. Loads are lazy
   and coalesced, recycled-row results are rejected, corrupt/expired entries are removed, and Forget
   clears both tiers while tab changes and same-Server reconnect retain useful entries. A six-hour
   freshness bound covers same-path artwork replacement. Full-size Player covers remain a separate
   future task and are not added to this cache.
3. **Completed — currently playing row indicator.** Library and Search mark only the row matched by
   the latest complete remote snapshot. Additive nullable `trackId`/`trackRealId` API v1 fields
   preserve raw entry identity and underlying `folder_files._id`; Phone exposes that row semantic
   as `underlyingId` only for track-capable wire types, so containers cannot match. Ordinary rows
   match only the underlying ID. Queue-ready matching additionally requires exact Queue category, entry ID, and
   underlying ID. Playlist-entry rows remain deliberately unmarked until the playback contract has
   a verified playlist-container identity, so duplicate metadata or entry IDs are never guessed.
   Confirmed state changes update only visible indicator views and do not replace the loaded row set.
   The first device report used a debug Server APK that predated these additive fields despite the
   unchanged version number. Matching-revision Server and Phone artifacts subsequently confirmed
   the indicator on device.
4. **Completed — representative category covers.** Visible Artists, Albums, Playlists and Folders
   derive a cover from the first usable artwork among at most six direct contained tracks. This is
   a visual aid, not official artist/entity artwork. The Phone reuses loaded container data or one
   bounded existing-API page, probes sequentially with coalescing, never recursively scans folders,
   and keeps a bounded Server/category/ID mapping while image bytes stay in the shared thumbnail
   cache. Expiring mappings, shorter missing/transient retry windows, Forget handling, and recycled-
   row/connection gates cover stale data and late results; neutral placeholders remain the fallback.
5. **Completed — shared mini-player.** One reusable layout/listener-renderer now sits above bottom
   navigation in Library, Search, and Settings. It consumes only the existing service replay of the
   confirmed state and current artwork, forwards Play/Pause through the existing binder, and opens
   Player through the shared navigation path. It is hidden without a confirmed track, retains the
   last snapshot while disconnected with controls disabled, clears old artwork on identity change,
   and adds only lifecycle-bound Settings binding—no second connection, MediaSession, polling loop,
   palette analysis, or thumbnail-cache path.
6. **Partially completed — Global categorized Search; provider-canonical device limitation remains.** The track-only Search presentation is replaced by three
   visually separated sections in fixed order: Tracks, Artists, Albums. Hide a section when it has
   no results. Track-title matches belong only to Tracks, artist-name matches only to Artists, and
   album-title matches only to Albums; deduplicate entity rows by their stable provider IDs. When
   the normalized query exactly equals one or more track titles, the Tracks section contains only
   those exact-title tracks rather than additional substring track matches. Artists verified as
   belonging to those exact tracks are also included in Artists even when their own name does not
   contain the query; direct artist-name matches remain included. Albums continue to be included
   from their own title matches. Thus `Obsidian` can show the exact `Northlane — Obsidian` track,
   `Northlane` in Artists through the verified relation, and the `Obsidian` album in Albums. Do not
   recover artist/album container IDs by comparing display strings on Phone: Server results must
   carry verified public provider identities and relations. A track retains the existing play
   action. Selecting an Artist or Album enters that existing Library container and its track list,
   preserving Search query/results so Back returns to the same Search state. Search all applicable
   provider rows before paging/section limits, keep stale-query protection, and add only additive
   backward-compatible API v1 fields or routes. The refinement now adds normalized `and`/`&`,
   diacritic and dash comparison, bounded two-error fuzzy fallback, related Albums, public
   `artists.is_unsplit` filtering, and an additive relation-aware Artist membership browse target;
   the historical Artist route stays unchanged. On the connected Poweramp build the canonical
   `Moe Shop` row is deduplicated and normalized/fuzzy probes find `Of Mice & Men`/`Northlane`, but
   `multi_artists` links collaboration files only to their composite IDs, those rows report
   `is_unsplit=0`, and no standalone `Sān-Z` ID exists. Therefore complete canonical collaboration
   merging and a standalone `Sān-Z` result cannot be implemented from stable public IDs on that
   library without forbidden display-string parsing. Keep this item partial until Poweramp emits
   split participant relations (or exposes another documented identity relation) and the complete
   Phone device matrix passes. Never reintroduce `/search?flt`.
7. **Search within the current scope.** Add a search action inside All tracks and individual
   folders, albums, artists and playlists, with an explicit visible scope. Define direct-folder
   versus recursive behavior before implementation. Search the whole selected container before
   pagination, not just the rows currently loaded on Phone. Verify public provider support and
   add only backward-compatible Server parameters where needed; bind continuation and stale-result
   protection to both query and scope. Never reintroduce the obsolete `/search?flt` path.
8. **Completed for current Library/Search rows — rounded thumbnails.** Track thumbnails,
   representative category covers, and their placeholders share an 8 dp outline clip, square
   proportions, and `centerCrop`, without bitmap reprocessing per bind. Mini-player artwork remains
   part of its separate future task.
9. **Per-list sorting.** Add sort selection for track lists, including within containers: title,
   album, artist, duration, and, only if publicly available and verified, date added and play count.
   Verify field semantics, units and provider ordering support before exposing each option; do not
   infer date added from an ID or invent play counts. Sort the entire scoped result before paging,
   use a stable tie-breaker, and bind page tokens to scope/query/sort/direction. Remember the chosen
   sort per relevant view, reset paging when it changes, and retain a provider-default order option.
   Sorting only an already downloaded page must not be presented as sorting the whole library.

Scoped search and sorting require a focused public Poweramp API/device check: the current Server
contract exposes global track search and provider-default ordering, not these additional options.
Unsupported criteria should remain unavailable with honest UI rather than fabricated values.

## Queue

Implement Queue in stages.

### Stage 1 — verified read-only operations

- retrieve current queue;
- display queue order;
- identify the currently playing item;
- play/select an existing queue entry.

Preserve Poweramp queue entry IDs because the same track may appear more than once.

Server Stage 1 is implemented with `read=true` and `playExisting=true`; exact provider ordering,
duplicate IDs, current-entry matching, and selection still require the recorded real-device matrix
before Phone Queue UI begins.

### Stage 2 — investigate mutations

Before promising editing support, verify documented/public mechanisms for:

- Add to Queue;
- Play Next;
- Remove from Queue;
- Reorder Queue.

Do not write directly to undocumented/internal Poweramp database structures and do not rely on unsupported MediaSession queue operations.

Only add mutation endpoints after their behavior has been verified on real Poweramp installations.

The current official sample demonstrates Add to Queue with public ContentProvider inserts plus
`ACTION_RELOAD_DATA`, but it is deliberately not exposed in Stage 1. No documented public Remove,
Reorder, or Play Next contract was found in the audited upstream source. All four mutation
capabilities therefore remain `false` until a separately scoped audit/implementation task.

## After the Library/Queue release — User-controlled connection policy

After the current Phone UI and Queue series is complete, publish and verify that release before
changing transport selection. Then add the following Phone settings together with the next focused
network-stack hardening work, before multi-player persistence and pairing hardening:

1. **Allow Wi-Fi Direct connections.** LAN remains the primary transport when this switch is on,
   with automatic Wi-Fi Direct fallback after the last infrastructure LAN path becomes unavailable.
   When it is off, stop Wi-Fi Direct discovery/negotiation and managed groups; loss of LAN leaves the
   Phone disconnected until LAN returns. Pairing flows that explicitly require pre-association
   Wi-Fi Direct need a clearly explained, deliberate temporary allowance rather than silently
   overriding this preference.
2. **Prefer Wi-Fi Direct.** Expose this switch only while Wi-Fi Direct is allowed. When enabled,
   establish and retain a verified direct endpoint even while a matching LAN endpoint is available;
   keep LAN discovery active as a bounded fallback, but do not automatically promote a healthy
   direct connection to LAN. When disabled, retain LAN-first behavior with Direct as fallback.

Persist the two switches as one validated internal policy with only three states: `LAN_ONLY`,
`LAN_PREFERRED`, and `DIRECT_PREFERRED`. This avoids an impossible configuration that prefers a
disabled transport and leaves room for one deterministic controller policy instead of independent
UI conditionals. Changing the policy must run through the existing `PhoneConnectionService` and
`RemoteClientController`, cancel obsolete retries, close only the superseded API/WebSocket
connection, and never create a second connection runtime or persist a transient address.

Add contextual actions to Player devices/connection controls:

- **Switch to Wi-Fi Direct** is disabled while already connected through Direct and unavailable
  while Wi-Fi Direct is globally disabled;
- **Switch to LAN** is disabled while already connected through LAN;
- when `DIRECT_PREFERRED` is active, switching manually to LAN asks for confirmation that the
  saved preference still favors Wi-Fi Direct;
- a confirmed manual switch is a temporary override for the current viable connection, not an
  implicit settings change. Clear it when that transport becomes unavailable, the service/device
  association is reset, or the user selects the other transport, then resume the saved policy.

The buttons must report the transport of the endpoint that actually completed the authenticated
API/WebSocket connection. An Android P2P group, discovered service, or pending socket by itself is
not a connected Direct endpoint. Direct preference must continue to respect permissions, Location
Mode, group-owner selection, system approval, identity matching, and LAN/API v1 compatibility.
Define bounded fallback behavior for direct discovery failure and unavailable manual targets before
implementation so preference never becomes an unexplained permanent offline state.

## After Library/Queue — Multi-player foundation and pairing hardening

- evolve the generic saved-device snapshot and one-slot preferences into an explicit collection;
- add selection and deterministic connection policy for more than one paired Server;
- retain stable identity as the key and never persist a resolved LAN/P2P address;
- consider an explicit Server-side paired-client/revocation surface;
- investigate application-layer authentication/encryption upgrades without breaking trusted-local
  API v1 clients or the embedded Web UI;
- continue respecting all mandatory Android/OEM Wi-Fi Direct confirmations.

## Lyrics

Implement lyrics after the library/queue foundation.

Poweramp's public `lyricsState` indicates availability but does not expose the actual lyrics text, so Server must use independent sources.

Planned sources:

1. sidecar `.lrc`;
2. embedded lyrics metadata;
3. optional future sources only if they can be integrated cleanly.

For synchronized LRC, Server should parse timestamps and expose structured lyric lines rather than repeatedly sending raw text.

Phone Client can then provide:

- full lyrics view;
- automatic scrolling;
- highlighted current line synchronized with remote playback position.

## Pairing and transport security

Continue supporting existing Bearer/session authentication for API v1 clients and the embedded Web UI.

For new pairing:

- permanent credentials are not displayed or embedded directly in QR codes;
- pairing secrets are one-time and short-lived;
- persistent credentials should be stored privately and excluded from backup where appropriate;
- avoid logging tokens or pairing secrets.

Investigate application-layer encrypted transport separately without breaking existing trusted-LAN API v1 compatibility.

## Further refinement

Continue improving:

- Phone and Web UI;
- accessibility;
- multi-player support;
- reconnection diagnostics;
- battery behavior;
- long-running stability;
- pairing security;
- compatibility across Android vendors and Poweramp versions.

Preserve the embedded Web UI and REST/WebSocket API v1 unless an explicitly versioned incompatible API is introduced.
