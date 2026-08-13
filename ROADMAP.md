# Poweramp Remote Roadmap

Current releases are Server `0.9.0` and Phone Client `0.3.0`; API remains `v1`.

Confirmed implementation and verification are tracked in `STATUS.md`. Server and Phone Client use independent application versions; API compatibility is tracked separately.

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

## Next — QR pairing and player-device management

Replace manual token copying with a simple first-run pairing flow.

Target UX:

1. Server displays a QR code.
2. Phone Client selects **Pair new player** and scans it.
3. QR identifies the Server but does not contain a permanent bearer token or fixed IP address.
4. Phone discovers the Server through LAN/NSD or Wi-Fi Direct.
5. A short-lived one-time pairing secret authenticates the initial exchange.
6. Server issues persistent credentials after successful pairing.
7. Subsequent connections remain automatic.

Suggested QR payload:

- persistent `serverId`;
- protocol/API version;
- short-lived one-time pairing secret;
- optional non-sensitive capability/version information.

Do not encode transient LAN/P2P addresses as identity.

Also add basic management for saved player devices:

- device name;
- connection/pairing status;
- forget/re-pair action;
- support for more than one paired Server without tying the architecture to a specific player model.

Mandatory Android Wi-Fi Direct confirmation dialogs must remain respected where required by the OS/OEM.

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

- permanent credentials must not be displayed or embedded directly in reusable QR codes;
- pairing secrets should be one-time and short-lived;
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