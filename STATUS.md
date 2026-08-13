# Current project status

Current version: 0.7.0

## Stage

The repository now builds two native Android applications:

- :app is the HiBy R4 foreground server, Poweramp integration, authenticated REST/WebSocket API,
  NSD publisher, and retained embedded Web UI.
- :phone is the first separate Android phone client. It discovers the server through NSD/mDNS,
  pairs with the existing Bearer token, stores the verified association locally, and consumes API
  v1 without integrating with Poweramp directly.

Wi-Fi Direct and Local Only Hotspot are intentionally not implemented. Both devices must be on the
same IP network.

## Implemented in version 0.7.0

- Added _poweramp-remote._tcp. publication on the R4 after the API listener binds. The TXT record
  contains only api=1 and a stable random public server id; it never contains the token.
- Kept NSD lifecycle inside the existing RemotePlaybackService. Publication stops and retries with
  the server runtime and does not create another foreground service or Poweramp integration path.
- Added the :phone application with Android NSD discovery/resolution. It matches a paired R4 by
  stable server identity and resolves the current address instead of storing an IP address.
- First connection accepts the existing 43-character Bearer token and persists it only after a
  successful authenticated GET /api/v1/state. Credentials use private preferences excluded from
  backup and device transfer; the reset action deletes the association.
- Subsequent launches automatically discover the known R4 and reconnect with bounded exponential
  backoff after a temporary network or WebSocket failure.
- Added a dependency-free Bearer REST/artwork client and RFC 6455 WebSocket client. Playback state
  comes from the initial WebSocket snapshot and subsequent complete event-driven snapshots; there
  is no periodic state polling. Ping/pong is used only to detect a dead connection.
- Added the native phone player screen with artwork, title, artist, album, elapsed/duration seekbar,
  Previous, state-aware Play/Pause, Next, one-shot seek, rating 0-5, Like, Dislike, Shuffle, and
  the main codec/file/source/list metadata from API v1.
- Phone commands reuse the existing REST command bodies and wait for confirmed WebSocket state
  instead of optimistically changing the authoritative state.
- Added parser, credential, discovery-contract, command, reconnect, formatting, REST loopback, and
  real WebSocket-upgrade tests for the phone client, plus server identity/NSD contract tests.

## Preserved 0.6.0 behavior

- The R4 remains one started-and-bound connectedDevice foreground service.
- API port 8765, all API v1 paths, JSON fields, command bodies, and HTTP status semantics are
  unchanged.
- External Bearer clients and the same-origin Web UI cookie/session authentication continue to use
  the existing implementation.
- The embedded Web UI, artwork endpoint, WebSocket client bounds, CSP, Origin checks, sessions, and
  transport/seek/rating/shuffle controls remain present.
- Poweramp metadata, artwork, transport, rating, shuffle, and event-driven refresh paths remain in
  the server module. Raw bitRate and raw positionInList are still passed through unchanged.

## Verification completed

Final clean verification completed on 2026-08-13 with JDK 17 and Android SDK 36:

    ./gradlew.bat clean :app:testDebugUnitTest :phone:testDebugUnitTest
      :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug
      --no-build-cache --console=plain

- Build succeeded with 100/100 Gradle tasks executed from a clean state.
- 76/76 JVM tests passed with no failures, errors, or skips across 19 suites:
  - R4/server module: 61/61 tests across 14 suites.
  - Phone module: 15/15 tests across 5 suites.
- Both lint reports say "No issues found."
- Loopback coverage verifies Bearer headers, state parsing, exact control JSON, artwork routing, a
  real WebSocket upgrade, and the initial full-state event.
- Source inspection confirms the phone module has no direct Poweramp integration and has only the
  one pairing-time REST state request; normal playback updates use WebSocket events.
- R4 APK: versionCode=7, versionName=0.7.0, minSdk=26, targetSdk=36, size 110,810 bytes,
  SHA-256 C8599311CAD22073753EC8475FE0ACDE1A60B39E0C259AB342CED927A9B24877.
- Phone APK: package dev.r4remote.poweramp.phone, versionCode=7, versionName=0.7.0,
  minSdk=26, targetSdk=36, size 71,881 bytes,
  SHA-256 45768DE9B0CD3159FF013805F8E6BDA5E87A24C3918B66284C02CB9D2F3D0F5C.
- APK manifests/badging confirm the separate packages, required network/multicast permissions, and
  the existing R4 connectedDevice foreground service.
- Both APKs verify with APK Signature Scheme v2 and one Android debug signer.

Delivery copies:

- outputs/R4-Poweramp-Remote-Server-v0.7.0-debug.apk
- outputs/Poweramp-Remote-Phone-v0.7.0-debug.apk

## Real-device checks still required

No HiBy R4/phone hardware pass is claimed by the automated verification. Before treating 0.7.0 as
device-validated:

- install both APKs, start Poweramp and the R4 server, and confirm the phone discovers the R4
  without entering an IP address;
- pair with the displayed token, restart the phone client, and verify automatic reconnection to the
  saved server identity;
- toggle Wi-Fi and change the R4 DHCP address, then confirm NSD supplies the new endpoint and the
  WebSocket recovers without duplicate commands or stale artwork;
- verify artwork, all metadata, seek, transport, rating/Like/Dislike, and Shuffle against Poweramp;
- keep the native client and existing Web UI connected together and repeat the 0.6.0 Web UI,
  Bearer/session, service-backgrounding, and R4 screen-off regression matrix;
- compare raw bitRate with known files and verify first/middle/last raw posInList before changing any
  units or index offsets.

## Known limitations

- HTTP and ws:// are plaintext. Version 0.7.0 is for a trusted local network only; do not expose
  port 8765 to the internet.
- Discovery currently requires one shared IP network. Direct Wi-Fi connection is future work.
- The phone connection lives with its Activity; MediaSession, a media notification, lock-screen
  controls, and watch control are roadmap items.
- Android force-stop, the R4 notification Stop action, or a device reboot stops the server until the
  server app is launched again.
- Exact public semantics of Poweramp bitRate units and the posInList index base remain unverified.
- Lyrics remain intentionally unimplemented in 0.7.0.

## Next scope

Continue 0.7.x with real-device discovery/pairing/reconnect fixes first. The longer-term sequence,
including Wi-Fi Direct/Local Only Hotspot research, MediaSession, volume, library/Queue, lyrics, and
security/UI/stability work, is recorded in [ROADMAP.md](ROADMAP.md).
