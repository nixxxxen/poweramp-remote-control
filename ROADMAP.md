# Poweramp Remote Roadmap

Server and Phone Client have independent version numbers. The current public versions are Server
`0.10.2` and Phone `0.6.0`; the fully validated release candidate is Server `0.11.0` and Phone
`0.7.0`. Local API `v1` remains backward-compatible.

Completed verification is recorded in [`STATUS.md`](STATUS.md). Durable architecture and protocol
rules live in [`PROJECT.md`](PROJECT.md).

## Released milestones

### Server 0.4.0–0.7.0 — local remote foundation

- authenticated local HTTP/WebSocket API and embedded same-origin Web UI;
- one persistent `connectedDevice` foreground service owning Poweramp integration;
- LAN NSD discovery and the first native Phone Client;
- playback, metadata, artwork, rating, shuffle, and seek controls.

### Server 0.8.0 / Phone 0.2.0 — Wi-Fi Direct fallback

- automatic Wi-Fi Direct discovery for the exact paired Server when LAN discovery fails;
- the same stable Server identity, credential, API `v1`, and control path on both transports;
- Android/OEM permission, Location Mode, group-owner, and approval requirements remain visible.

### Server 0.8.1 / Phone 0.2.1 — autonomous recovery

- peer/service discovery and reconnect moved fully into the existing foreground services;
- background, screen-off, channel-loss, and existing-group recovery no longer depend on Activities.

### Server 0.9.0 / Phone 0.3.0 — Android media integration

- player-device system media-volume state and control;
- one Phone Media3 session for notification, lock-screen, and compatible Wear OS controls;
- complete LAN-to-Wi-Fi-Direct transport reinitialization without a second runtime.

### Server 0.10.0 / Phone 0.4.0 — secure pairing and Phone surfaces

- address-free QR pairing with a two-minute one-time secret and stable Server identity;
- manual Bearer-token fallback, Player devices diagnostics, re-pair, and forget;
- service-owned playback-position restoration and the compact native Phone player.

### Phone 0.4.1 — lifecycle and layout fixes

- binder-independent scanner handoff and reliable manual pairing fallback;
- safe system/cutout/navigation insets and compact-screen player corrections.

### Server 0.10.1 / Phone 0.4.2 — pairing hardening

- strict `org.json` QR request parsing and scanner-delivery deduplication;
- socket-receipt playback timestamps, scanner orientation handling, and square artwork;
- unchanged credentials, transports, service architecture, and API `v1`.

### Server 0.10.2 / Phone 0.5.0 — localization and navigation

- complete English/Russian Phone presentation and English Server/Web UI;
- persisted System default/Russian/English app-language selection;
- Settings, About, Player devices, and signed in-place upgrade compatibility.

### Server 0.10.2 / Phone 0.6.0 — player presentation

- artwork-derived dark theme, cached artwork swipe navigation, and track transitions;
- animated controls and smooth locally extrapolated playback progress;
- compact LAN/Wi-Fi Direct status and stable metadata-chip styling.

## Server 0.11.0 / Phone 0.7.0 — Library, Search, and Queue

The release candidate is implemented and has passed the complete signed in-place device matrix.

- Library browsing for All tracks, Artists, Albums, folder hierarchy, and Playlists;
- lazy bounded pages with no fixed total-row ceiling, cached track/category artwork, exact current-
  row identity, and stable state across navigation and reconnect;
- per-list sorting by Poweramp order, title, album, artist, duration, date added, and play count in
  both directions;
- categorized Global Search for Tracks, Artists, and Albums with independent continuation,
  punctuation/diacritic normalization, bounded typo matching, multi-artist relations, structured
  `artist - track/album` queries, and removable local history;
- shared mini-player and content-only transitions across Player, Library, Search, and Settings;
- Queue browsing in Poweramp order, exact duplicate occurrence playback/current indication, and
  Queue-only `1/N…N/N` presentation;
- public single and ordered batch Add to Queue for up to 100 track-capable rows, preserving
  duplicates without starting playback;
- Server `0.11.0` / code 14 and Phone `0.7.0` / code 16 retain the legacy application IDs,
  permanent release certificate, pairing data, one-service architecture, and API `v1`.

## Planned after 0.11.0 / 0.7.0

### 1. User-controlled connection policy and network hardening

Add two Phone settings through one validated three-state policy:

- **Allow Wi-Fi Direct connections**: off means `LAN_ONLY`; on permits Direct fallback;
- **Prefer Wi-Fi Direct**: on means `DIRECT_PREFERRED`; off with Direct allowed means
  `LAN_PREFERRED`.

`DIRECT_PREFERRED` should keep a healthy authenticated Direct connection even when LAN is
available, while retaining LAN discovery as bounded fallback. The UI must not allow the impossible
state “prefer a disabled transport”. Pairing that explicitly needs pre-association Direct may offer
a clearly explained temporary allowance, never silently override the saved policy.

Add contextual actions:

- **Switch to Wi-Fi Direct**, disabled while already on Direct or while Direct is disallowed;
- **Switch to LAN**, disabled while already on LAN;
- when Direct is preferred, manual LAN switching asks for confirmation;
- a confirmed manual choice is temporary and clears when that transport becomes unavailable, the
  paired device changes, or the user chooses the other transport.

All policy changes stay inside the existing `PhoneConnectionService` and
`RemoteClientController`. Connected transport is reported only after an identity-checked,
authenticated API/WebSocket connection succeeds—not from a P2P group or discovered endpoint.
Permissions, Location Mode, Android/OEM approval, group-owner selection, and bounded fallback must
remain recoverable and user-visible.

### 2. Multi-player foundation and pairing hardening

- evolve one-slot pairing storage into an explicit collection keyed by stable Server identity;
- add deterministic active-device selection without persisting LAN/P2P addresses;
- consider Server-side paired-client visibility and credential revocation;
- improve reconnect diagnostics and broader Android/OEM coverage without adding another service or
  Poweramp integration path.

## Deferred or on demand

### Scoped Search

Global Search covers the primary use case, so search inside a selected Artist, Album, Folder,
Playlist, or All tracks view is deferred until requested. A future implementation must search the
whole scope before paging, define direct-folder versus recursive behavior, preserve stale-result
gates, and never restore the crashing `/search?flt` route.

### Additional Queue mutations

Remove, Clear, Reorder, and Play Next remain unavailable. The audited public Poweramp API documents
Add to Queue but no supported external contract for those operations. Do not use internal database
access, hidden intents, MediaSession queue mutations, or UI automation. Revisit only if Poweramp
publishes a suitable API or a separately approved public-contract investigation succeeds.

### Lyrics

Poweramp exposes lyrics availability but not lyrics text through the audited public API. A future
implementation may consider sidecar `.lrc` first and embedded metadata second, then expose parsed
timestamped lines for a synchronized Phone view. It must not introduce an uncontrolled cloud
dependency.

### Transport security and general refinement

Investigate application-layer encryption without breaking trusted-local API `v1` or the embedded
Web UI. Continue improving accessibility, battery behavior, long-running stability, diagnostics,
and compatibility across Android vendors and Poweramp versions.
