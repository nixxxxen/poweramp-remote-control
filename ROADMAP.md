# Poweramp Remote Roadmap

Current releases are Server `0.9.0` and Phone Client `0.3.0`; API remains `v1`. Confirmed
implementation and verification are tracked in `STATUS.md`.

## 0.9.0 Server / 0.3.0 Phone — Remote system integration

Implemented:

- Server exposes the player device's Android media-stream current/max volume as optional API v1
  fields and accepts `set_volume`; the official Poweramp Intent API audit found no public volume
  command, so no private Poweramp constant is used.
- Embedded Web UI and Phone Client provide compact, WebSocket-synchronized remote volume sliders.
- Phone Client publishes one Media3 MediaSession backed by a custom remote `SimpleBasePlayer`:
  metadata, artwork, state, duration, and position come from API v1; playback/seek commands return
  through API v1. It has no ExoPlayer, audio output, or audio-focus ownership.
- Android notification/lock-screen and compatible Wear OS controllers receive previous,
  play/pause, next, and seek from the same foreground Phone runtime.
- LAN recovery now keys off actual Wi-Fi/Ethernet availability, replaces stale LAN endpoints, fully
  reinitializes Phone P2P discovery/channel state, and refreshes Server DNS-SD publication before
  direct fallback instead of relying on a Server restart.

Remaining release validation:

- Verify remote volume changes in both directions, including player hardware buttons, fixed-volume
  devices, screen-off operation, and the target HiBy R4 step range.
- Verify MediaSession metadata/artwork/position/actions in Android notification, lock screen, and a
  compatible Wear OS controller across LAN, P2P, reconnect, pause, track change, and process restart.
- Reproduce shared-LAN loss and confirm automatic LAN → P2P fallback without restarting Server.

## 0.8.x Server / 0.2.x Phone — Connection hardening

Implemented in Server `0.8.1` / Phone Client `0.2.1`:

- Server-owned peer discovery keeps the DNS-SD publisher visible without opening Android Settings.
- Phone peer discovery, service discovery, P2P receivers/channel, group monitoring, API WebSocket,
  and reconnect now live in a `connectedDevice` foreground service rather than Activity lifecycle.
- Discovery action/channel failures use bounded automatic recovery; a previously established direct
  group automatically reconnects after temporary loss without looping first-approval dialogs.

Remaining real-device validation and hardening:

- Validate LAN-first discovery and automatic Wi-Fi Direct fallback on real Android devices.
- Cover Android 8–12L location permissions/Location Mode and Android 13+ Nearby devices permission.
- Verify first system approval, reconnect to a persistent P2P group, denial, timeout, Wi-Fi off/on,
  and vendor-specific group-owner selection.
- Verify automatic recovery when LAN returns, DHCP changes, or a direct group disappears.
- Improve diagnostics without exposing the token or persisting transient addresses.
- Investigate a standards-based fallback for devices that repeatedly select the phone as group
  owner; do not hard-code the conventional Wi-Fi Direct IPv4 address.
- Evaluate Local Only Hotspot only if Wi-Fi Direct proves unreliable on target devices. It must be
  an explicit user-visible fallback and must not replace ordinary LAN/NSD.

## Pairing and transport security

- Replace manual token copying with a stronger authenticated pairing UX when a compatible API
  evolution is designed.
- Investigate application-layer encrypted transport while keeping API v1 compatibility for current
  trusted-LAN clients.

## Library and queue

Add documented/verified library browsing, search, selected-track playback, queue viewing, and queue
operations.

## Lyrics

Add LRC and embedded lyrics only after a verified lyrics source is available; Poweramp's public
`lyricsState` alone is not lyrics content.

## Further refinement

Continue UI, accessibility, connection diagnostics, battery behavior, long-running stability, and
security improvements while preserving REST/WebSocket API v1 and the embedded Web UI.
