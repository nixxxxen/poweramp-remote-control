# Poweramp Remote Roadmap

Current releases are Server `0.8.1` and Phone Client `0.2.1`; API remains `v1`. Confirmed
implementation and verification are tracked in `STATUS.md`.

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

## MediaSession in Phone Client

Add MediaSession, media notifications, lock-screen controls, and smartwatch playback control.

## Volume control

Research a documented Poweramp/Android volume mechanism and implement it across Server API, Web UI,
and Phone Client without unverified constants.

## Library and queue

Add documented/verified library browsing, search, selected-track playback, queue viewing, and queue
operations.

## Lyrics

Add LRC and embedded lyrics only after a verified lyrics source is available; Poweramp's public
`lyricsState` alone is not lyrics content.

## Further refinement

Continue UI, accessibility, connection diagnostics, battery behavior, long-running stability, and
security improvements while preserving REST/WebSocket API v1 and the embedded Web UI.
