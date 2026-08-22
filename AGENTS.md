# AGENTS.md

## Purpose

This repository contains Poweramp Remote for Android: a native foreground Server installed on an
Android player device with Poweramp, plus a separate native Phone Client.

Current application versions are independent:

- Server: `0.10.2` (`versionCode 13`);
- Phone Client: `0.5.0` (`versionCode 14`);
- local API: `v1` (unchanged).

The legacy Android application IDs under `dev.r4remote` are intentionally retained only for
in-place upgrade compatibility, so existing Server tokens and Phone pairing preferences survive.
Both legacy apps previously shipped `versionCode 7`; the Server counter is now `13` and the Phone
counter is `14`. Future Server and Phone codes must continue to advance separately. The legacy IDs
are not the current product or source namespace.

## Read first

Before any non-trivial change:

1. Read `PROJECT.md`.
2. Read `STATUS.md`.
3. Read `ROADMAP.md` when changing planned scope.
4. Inspect the existing implementation before adding a new integration path.
5. Preserve all functionality confirmed working in version `0.4.0`.
6. Prefer documented/public Poweramp and Android APIs.
7. Do not assume an API field, unit, index base, event, command, permission, or network behavior
   unless verified by documentation, source/API definitions, tests, or device behavior.

## Current architecture

The `:app` module is Poweramp Remote Server. Its one in-process started-and-bound
`RemotePlaybackService` owns Poweramp integration, system media volume, playback state, local API
v1, embedded Web UI, LAN NSD publication, and Wi-Fi Direct DNS-SD publication. Do not create another
foreground service or a second Poweramp integration path.

The `:phone` module is the native Phone Client. Its one started-and-bound
`PhoneConnectionService` is both a `connectedDevice` and `mediaPlayback` foreground service. It owns
LAN discovery, Wi-Fi Direct, API/WebSocket connections, reconnect state, and one Media3
`MediaSession` independently of the Activity. Its custom `SimpleBasePlayer` is only a remote proxy:
it never decodes audio, requests audio focus, or integrates with Poweramp directly. It discovers
`_poweramp-remote._tcp` through ordinary LAN NSD first. Initial/re-pair scans an address-free QR
containing the stable Server identity and a short-lived one-time secret, discovers only that target
through existing LAN or Wi-Fi Direct paths, exchanges the secret through API v1, and privately
stores the returned Bearer credential. Normal operation consumes the existing API v1
REST/artwork/WebSocket routes.

Scanner results and manual-token fallback requests are submitted as private start commands to that
same service and pass through its service-owned request state; they must never depend on whether an
Activity binder happens to be connected. Manual pairing verifies the existing persistent Bearer
credential against LAN-discovered Servers and does not introduce address entry or a second
connection runtime.

The WebSocket reader records the monotonic receipt time of each complete remote snapshot before
posting it to the main looper. `PhoneConnectionService` turns that pair into one playback-position
anchor shared by the rebound player UI and `RemoteSessionPlayer`; neither consumer may replace it
with its later callback-delivery time.

For a previously paired Server or the exact identity in an active QR offer, the Phone Client starts
Wi-Fi Direct service discovery when the target is not found through LAN NSD. The Server advertises the same public stable
identity, API version, and listener port through pre-association Wi-Fi Direct DNS-SD. The credential
is never advertised. After Android forms a P2P group, the Phone Client uses the group-owner address
with the unchanged API v1 client.

LAN remains preferred. Network callbacks restart NSD after network changes; a recovered LAN
endpoint replaces a direct endpoint. Wi-Fi Direct connection requests must respect runtime
permissions, Location Mode requirements, platform group-owner selection, and any system approval
shown on either device. Never try to bypass or automate those dialogs.

Version history:

- `0.4.0` added the authenticated HTTP/WebSocket API;
- `0.5.0` added the embedded same-origin Web UI and browser sessions;
- `0.6.0` moved the runtime into one `connectedDevice` foreground service;
- Server `0.7.0` introduced LAN NSD and the initial Phone Client;
- Server `0.8.0` / Phone Client `0.2.0` add automatic Wi-Fi Direct fallback while retaining LAN,
  pairing, authentication, Web UI, and API v1.
- Server `0.8.1` / Phone Client `0.2.1` make peer/service discovery autonomous and move the Phone
  connection runtime into a `connectedDevice` foreground service for background persistence and
  automatic recovery.
- Server `0.9.0` / Phone Client `0.3.0` add player-device system media volume, a remote Media3
  session for Android/Wear controls, and complete LAN-to-P2P transport reinitialization while
  keeping API v1 backward compatible.
- Server `0.10.0` / Phone Client `0.4.0` add address-free one-time QR pairing over the existing
  LAN/P2P discovery paths, service-owned playback-position restoration after Activity rebind, a
  separate Player devices surface, and a compact playback-only Phone UI while keeping API v1
  backward compatible.
- Phone Client `0.4.1` fixes the scanner-result/service-binding race, restores manual Bearer-token
  pairing fallback, applies safe system/cutout/navigation insets, and guarantees the compact player
  keeps its volume control visible. Server remains `0.10.0` and API remains `v1`.
- Server `0.10.1` / Phone Client `0.4.2` replace the Android-incompatible static-regexp QR request
  parser with validated `org.json`, keep failures behind a logged Server request boundary,
  deduplicate each service-owned scanner-launch delivery by UUID, preserve the WebSocket receipt
  timestamp for shared UI/MediaSession extrapolation, choose scanner orientation from the calling
  Phone screen, and enforce square artwork. API remains `v1` and the transport/runtime architecture
  is unchanged.
- Server `0.10.2` / Phone Client `0.5.0` complete every Server Android/Web surface in English,
  provide complete English-fallback and Russian Phone resources, add a compact main menu plus
  presentation-only Settings/About screens, and add persisted app-language selection through
  platform `LocaleManager` on Android 13+ with a configuration-context fallback on API 26–32.
  Pairing, credentials, discovery, services, MediaSession, notification controls, and API `v1`
  remain compatible with the first public release.

Do not introduce a cloud dependency, duplicate Poweramp path, duplicate Server service, protocol
fork, or unrelated architectural rewrite unless explicitly requested.

## Regression-sensitive baseline: Server 0.10.2 / Phone Client 0.5.0

The following functionality is implemented and working:

### Track metadata

- title;
- artist;
- album;
- album artwork;
- codec / file type;
- bit depth;
- sample rate;
- bitrate;
- source category;
- position in the current list.

### Playback state and automatic updates

- playback state;
- metadata refresh on `TRACK_CHANGED`;
- metadata/status refresh on `STATUS_CHANGED`;
- shuffle state refresh on `PLAYING_MODE_CHANGED`.

### Transport controls

- play;
- pause;
- previous track;
- next track.

### Rating controls

- rating values `0…5`;
- Like;
- Dislike;
- exact Poweramp `SET_RATING` command.

### Shuffle

- binary shuffle OFF/ON control;
- shuffle state tracking through `PLAYING_MODE_CHANGED`.

### Player device and Android integration

- exact player-device system media-volume state/control;
- one remote Media3 session for Android notification/lock-screen and compatible Wear controls;
- Activity resume/rebind restoration from the service-owned playback snapshot without polling;
- one receipt-time playback-position model shared by the Phone UI and MediaSession.

### Pairing and Phone surfaces

- short-lived, one-time QR pairing with no persistent credential or address in the QR;
- manual Bearer-token fallback for devices without a usable camera;
- exact target discovery through LAN first and Wi-Fi Direct fallback;
- separate generic `Player devices` screen with status, transport, diagnostics, re-pair, and forget;
- playback-only, non-scrolling main Phone screen with metadata chips and compact controls;
- symmetric main-menu and Player devices buttons plus presentation-only Settings/About screens;
- persisted System default / Russian / English Phone language selection that never clears pairing or
  restarts the connection runtime;
- complete English-fallback and Russian Phone resources, including scanner/dialog/status/error,
  accessibility, foreground-notification, and notification-channel presentation;
- safe system-bar/display-cutout/navigation insets on every Phone presentation screen;
- scanner-only portrait default with landscape retained when the calling app screen is landscape;
- square rounded artwork at every available player-screen size;
- English-only Server Activity, foreground notification/channel, and embedded Web UI.

### Intentionally not implemented

- Library;
- Queue;
- Lyrics;
- full multi-player persistence/selection.

These remain out of scope unless explicitly requested.

## Known unresolved details

The exact public semantics of these Poweramp fields are not fully guaranteed:

- units of `bitRate`;
- index base of `posInList`.

API v1 must continue to preserve both raw values. Presentation code may format bitrate and remove
diagnostic labels from list position, but must not invent an unverified index offset or alter the
network payload.

## Implementation rules

For every new capability:

1. Locate and reuse the existing integration and event/update paths.
2. Keep Poweramp-specific constants and parsing isolated from presentation code.
3. Handle absent or unsupported optional values gracefully.
4. Preserve automatic event-driven refresh and avoid polling when an event is available.
5. Avoid magic numbers when official constants exist.
6. Keep API v1 routes, payloads, authentication, and status semantics compatible.
7. Keep LAN NSD active and preferred when changing direct-connect behavior.
8. Match Wi-Fi Direct peers by the already verified stable Server identity before connecting.
9. Never advertise or persist a resolved address as pairing identity.
10. Treat Android permission denial, Location Mode off, Wi-Fi off, unsupported P2P, approval
    timeout, or unfavorable group-owner selection as user-visible recoverable states.

## Verification

For meaningful changes:

- run both modules' unit tests;
- run lint for both modules;
- perform a clean debug build of both APKs;
- verify manifests, version metadata, and APK signatures;
- preserve the Poweramp metadata/control and Web UI regression suites;
- add tests for new pure contracts, parsing, formatting, and state policies;
- update `STATUS.md` with exact results and remaining real-device checks.

Use repository build scripts and the pinned wrapper. Do not invent a parallel build workflow.

## Project memory

`PROJECT.md` is durable architecture and behavior documentation. `STATUS.md` is the concise current
handoff. `ROADMAP.md` records future scope. After a meaningful completed task, update all affected
documents with versions, implementation, verification, limitations, and next unresolved work.

## Decision priority

When information conflicts, use this priority:

1. explicit instructions in the current user task;
2. `AGENTS.md`;
3. `PROJECT.md`;
4. `STATUS.md`;
5. current repository implementation;
6. assumptions.

If documentation and code disagree, inspect the code and correct the documentation when appropriate.
