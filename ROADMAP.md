# Poweramp Remote Roadmap

Current public, release-validated versions are Server `0.10.2` and Phone Client `0.5.0`; API remains
backward-compatible `v1`. Post-release presentation work below intentionally keeps those versions.

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

## Post-0.5.0 Phone UI improvement — dynamic artwork theme

Implemented locally without changing Server `0.10.2`, Phone `0.5.0`, version codes, or API `v1`:

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

Control morphs/pulses, seek animation, a new connection indicator, and artwork-dependent control/
chip colors remain separate future UI work.

## Post-0.5.0 Phone UI improvement — artwork swipe and unified track transition

Implemented locally as the second stage of the future Phone `0.6.0` UI series, while the declared
versions remain Server `0.10.2` / Phone `0.5.0` and API `v1`:

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

## Next — Multi-player foundation and pairing hardening

- evolve the generic saved-device snapshot and one-slot preferences into an explicit collection;
- add selection and deterministic connection policy for more than one paired Server;
- retain stable identity as the key and never persist a resolved LAN/P2P address;
- consider an explicit Server-side paired-client/revocation surface;
- investigate application-layer authentication/encryption upgrades without breaking trusted-local
  API v1 clients or the embedded Web UI;
- continue respecting all mandatory Android/OEM Wi-Fi Direct confirmations.

## Library browsing and search

Treat Server/Poweramp as the source of truth. Do **not** replicate the entire Poweramp database to Phone.

Expose a paged/lazy library API backed by the documented Poweramp ContentProvider.

Initial browsing structure:

- Artists;
- Albums;
- Folders;
- Playlists;
- Search.

Load only the currently requested category/page and cache recent results locally for responsive navigation.

Search should execute on the Server through Poweramp's documented search facilities and return grouped results where practical:

- artists;
- albums;
- tracks;
- playlists.

Support playback actions for documented Poweramp content URIs:

- play selected track;
- start an album from a selected track;
- start a playlist;
- start another supported category/list.

Artwork should be loaded lazily rather than transferred for the whole library.

## Queue

Implement Queue in stages.

### Stage 1 — verified read-only operations

- retrieve current queue;
- display queue order;
- identify the currently playing item;
- play/select an existing queue entry.

Preserve Poweramp queue entry IDs because the same track may appear more than once.

### Stage 2 — investigate mutations

Before promising editing support, verify documented/public mechanisms for:

- Add to Queue;
- Remove from Queue;
- Reorder Queue.

Do not write directly to undocumented/internal Poweramp database structures and do not rely on unsupported MediaSession queue operations.

Only add mutation endpoints after their behavior has been verified on real Poweramp installations.

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
