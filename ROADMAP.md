# Poweramp Remote Roadmap

Current releases are Server `0.10.1` and Phone Client `0.4.2`; API remains backward-compatible `v1`.

Confirmed implementation and verification are tracked in `STATUS.md`. Server and Phone Client use independent application versions; API compatibility is tracked separately.

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

Remaining release validation is the real-device/OEM matrix in `STATUS.md`, especially scanner
camera flow, QR expiry/reuse, LAN and pre-association P2P initial pairing, Activity recreation, and
short-screen/accessibility behavior.

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
