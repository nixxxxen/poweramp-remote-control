# Current project status

Current versions:

- Server: `0.8.0` (`versionCode 8`)
- Phone Client: `0.2.0` (`versionCode 8`)
- API: `v1` (unchanged)

## Stage

The repository builds two native Android applications. `:app` is the universal Poweramp Remote
Server for an Android player device with Poweramp. `:phone` is the native Phone Client. Ordinary
LAN/NSD remains preferred; a previously paired Server can now be discovered and reached through
Wi-Fi Direct when it is not available in the shared LAN.

## Implemented in Server 0.8.0 / Phone Client 0.2.0

- Split module versions: Server `8 / 0.8.0`, Phone Client `8 / 0.2.0`. Both counters advance from
  the previously shipped code `7` but are now declared and maintained independently.
- Kept legacy `applicationId` values only to preserve update compatibility, tokens, and pairing;
  moved source namespaces, Gradle root name, theme, UI, comments, and current docs to neutral
  Poweramp Remote terminology.
- Removed device-specific naming from product UI, embedded Web UI, NSD text, code comments, and
  current documentation. Historical release context is retained only where it describes history.
- Preserved LAN `_poweramp-remote._tcp.` publication and LAN-first discovery.
- Added Server pre-association Wi-Fi Direct DNS-SD publication inside the existing
  `RemotePlaybackService` lifecycle. It publishes only `api=1`, stable public `id`, and `port=8765`;
  it never publishes the Bearer token.
- Added paired-identity-only Wi-Fi Direct discovery in the Phone Client after a short LAN grace
  period. Unknown identities, wrong API versions, invalid ports, and malformed domains are ignored.
- Added one bounded automatic P2P connection attempt with minimum phone group-owner intent. Android
  remains responsible for group negotiation and mandatory system confirmations.
- Uses `WifiP2pInfo.groupOwnerAddress` as a transient direct endpoint and then reuses the unchanged
  Bearer REST/artwork/WebSocket API v1 clients.
- Added explicit UI states/actions for Nearby devices/location permission, Location Mode, Wi-Fi,
  unsupported P2P, discovery/connect timeout, and unfavorable phone group-owner selection. No
  system dialog is bypassed and failed automatic attempts do not loop indefinitely.
- Added default-network monitoring. LAN NSD restarts after network loss/restoration; direct group
  loss returns to LAN discovery/direct fallback; existing WebSocket retry remains bounded.
- Added current Android permission model: `NEARBY_WIFI_DEVICES` + `neverForLocation` on Android 13+;
  `ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION` through API 32; optional Wi-Fi Direct feature.
- Fixed Phone Client metadata presentation: bitrate uses human-readable `кбит/с`, and valid list
  positions display as `current / total`, with no diagnostic `list`/`raw` labels or guessed offset.
- Added tests for Wi-Fi Direct public TXT validation, token exclusion, and human-readable metadata.

## Preserved behavior

- One started-and-bound `connectedDevice` Server foreground service and one Poweramp integration.
- Port `8765`, every API v1 route, JSON field, command body, and HTTP status semantic.
- Raw `bitRate` and `positionInList` values remain unchanged in API v1.
- External Bearer auth, browser session cookie auth, CSP, same-origin checks, session bounds, and
  WebSocket client bounds.
- Embedded Web UI and all metadata, artwork, transport, seek, rating/Like/Dislike, and shuffle.
- Phone pairing persists credentials only after authenticated validation and never persists IP.
- Event-driven full snapshots without playback polling or optimistic authoritative state.

## Verification

Development verification on 2026-08-13 with the pinned Gradle wrapper, JDK 17, and Android SDK 36:

- The final offline clean pipeline completed successfully:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache`.
- All `82/82` JVM tests passed across 20 suites: Server `62/62` in 14 suites and Phone Client
  `20/20` in 6 suites, including the new Wi-Fi Direct contract and formatting tests.
- `:app:lintDebug` and `:phone:lintDebug` both report `No issues found`.
- Gradle executed 100 clean-build tasks; both debug APKs assembled successfully.
- Source and test packages compile under `dev.powerampremote.server` and
  `dev.powerampremote.phone`; manifests reference the explicit new component classes while retaining
  legacy application IDs.
- APK badging confirms Server `versionCode=8`, `versionName=0.8.0` and Phone Client
  `versionCode=8`, `versionName=0.2.0`; both target API 36, require at least API 26, and declare
  Wi-Fi Direct/Wi-Fi/location hardware as optional.
- APK Signature Scheme v2 verification passed for both debug APKs. Both use the repository debug
  signer with SHA-256 certificate digest
  `fe18eb98d47f95ee3b38185d239d9961298549f20ed7c3b6c45f118f11cf546e`.
- Delivery artifacts:
  - `outputs/Poweramp-Remote-Server-v0.8.0-debug.apk` — 114,866 bytes, SHA-256
    `f50c5552b44bda46c56c16a1a8a652a57db6c8a5ccb19b62585f38880e41a323`.
  - `outputs/Poweramp-Remote-Phone-v0.2.0-debug.apk` — 90,093 bytes, SHA-256
    `697c33a0b0aba9490fced1316f719d6cb5458569ca01a2de0c15c054a095f233`.

## Real-device checks required

No Wi-Fi Direct hardware pass is claimed by JVM/build verification. Before release validation:

- Upgrade existing installations and confirm the Server token and Phone pairing survive.
- Verify first LAN pairing, normal LAN WebSocket operation, DHCP address change, Wi-Fi off/on, and
  automatic reconnect.
- With no shared LAN, grant Android 13+ Nearby devices or Android 8–12L location permissions and
  enable Location Mode; confirm discovery of only the saved Server identity.
- Accept the platform P2P prompt if shown, confirm the Poweramp device becomes group owner, and
  verify REST, artwork, WebSocket, and every control over Wi-Fi Direct.
- Test rejection/timeout, unsupported hardware, phone selected as group owner, direct disconnect,
  and LAN restoration without repeated system prompts or stale endpoints.
- Test representative vendor devices across Android 8–16; Wi-Fi Direct persistence and approval
  behavior are OEM-dependent.
- Run the complete embedded Web UI, Bearer/session, screen-lock/backgrounding, and simultaneous Web
  + native client regression matrix.
- Compare API `bitRate` with known files and first/middle/last `positionInList` values before any
  future protocol conversion.

## Known limitations

- HTTP and `ws://` are plaintext at the application layer. Do not expose port `8765` to the internet.
- Initial token pairing still requires a shared LAN; direct-connect is deliberately limited to an
  already verified Server identity.
- The current direct IPv4 route requires the Poweramp device to be P2P group owner. Android may
  choose otherwise despite the client preference; the UI then asks the user to retry/use LAN.
- Wi-Fi Direct discovery requires system Location Mode even on current Android, per platform rules.
- The Phone Client connection still follows Activity lifecycle; MediaSession/notification controls
  remain roadmap work.
- Force-stop, Server notification Stop, or reboot stops the Server until it is launched again.
- Exact public semantics of Poweramp bitrate units and list index base remain unverified.
- Lyrics remain intentionally unimplemented.

## Next scope

Complete real-device connection validation and vendor-specific fixes for Server `0.8.x` / Phone
Client `0.2.x`, then continue the priorities in [`ROADMAP.md`](ROADMAP.md).
