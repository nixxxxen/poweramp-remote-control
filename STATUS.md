# Current project status

Current versions:

- Server: `0.9.0` (`versionCode 10`)
- Phone Client: `0.3.0` (`versionCode 10`)
- API: backward-compatible `v1`

## Stage

The repository builds two native Android applications. `:app` is the foreground Server on the
Poweramp/player device; `:phone` is the native remote client. This stage adds player-device volume,
an Android Media3 session on the Phone, and deterministic LAN → Wi-Fi Direct recovery without
changing existing routes, authentication, or pairing identity.

## Implemented in Server 0.9.0 / Phone Client 0.3.0

### Player-device volume

- Re-audited the official Poweramp API source snapshot through Poweramp build `1026-beta`. Its
  public Intent commands contain no volume operation; internal DSP/skin identifiers are not used.
- Added one service-owned `SystemMediaVolumeController` on Server. It reads and sets only
  `AudioManager.STREAM_MUSIC` on the player device, reports fixed/unavailable volume safely, and
  observes Android system-setting changes so hardware-button changes produce event-driven state.
- Appended optional `volume`, `volumeMax`, and `volumeControlAvailable` fields to complete API v1
  state snapshots and added `{"action":"set_volume","value":N}`. Existing fields, routes, Bearer
  auth, browser sessions, status codes, WebSocket format, and old API v1 clients remain compatible.
- Added compact confirmed-state volume sliders to the embedded Web UI and Phone UI. A short pending
  window prevents an older snapshot from jumping under the user's finger, then reverts if Server
  does not confirm the value. Phone never accesses its own `AudioManager`.

### Phone MediaSession

- `PhoneConnectionService` now extends Media3 `MediaSessionService` and remains the one owner of all
  Phone runtime state. It is a combined `connectedDevice|mediaPlayback` foreground service with one
  persistent media/connection notification; Activity lifecycle still owns no transport teardown.
- Added a custom `SimpleBasePlayer` proxy, not ExoPlayer. Metadata, artwork, playing/paused, duration,
  and confirmed position come from the existing WebSocket state; position advances locally from the
  confirmed anchor while playing. Previous, play/pause, next, and seek return through REST API v1.
- MediaSession artwork is downscaled/re-encoded to a bounded Binder-safe payload. The standard
  Android session endpoint is exported for System UI, lock-screen, and compatible Wear controllers;
  the private Activity binder exposes no cross-process transaction protocol.
- The custom player declares remote `DeviceInfo`. MediaSession volume reads/commands therefore map
  to the Server's player-device volume rather than the phone media stream.
- No decoder, fake playback engine, audio output, audio-focus request, keep-screen-on flag, wakelock,
  or Wi-Fi lock was added.

### LAN → Wi-Fi Direct recovery

- Fixed the Phone recovery decision that treated any default network, including cellular, as proof
  that the old LAN endpoint was still routable. Wi-Fi/Ethernet callbacks now invalidate stale LAN
  candidates, close the old socket, restart NSD, and trigger direct fallback immediately when no
  LAN-capable network remains.
- A new fallback cycle clears old P2P requests/negotiation and uses a fresh
  `WifiP2pManager.Channel`. Channel generations prevent a delayed callback from an old closed
  channel from clearing the replacement; managed-group removal is bounded before final release.
- Server monitors Wi-Fi/Ethernet transitions, clears and republishes its P2P DNS-SD record, restarts
  peer discovery, and rebuilds a failed channel. Refresh is deferred while an established group is
  connected.
- Added diagnostic logs for LAN route evaluation, endpoint invalidation, publication refresh,
  discovery/channel generations, group cleanup, and fallback restart. Tokens are never logged and
  transient addresses are not persisted.

## Preserved behavior

- One Server foreground service and one Poweramp integration path; one Phone foreground service.
- LAN-first `_poweramp-remote._tcp.` discovery, verified-identity Wi-Fi Direct fallback, automatic
  reconnect, and system-owned approval/group-owner decisions.
- Port `8765`, every API v1 route and prior JSON field/command, Bearer auth, browser session auth,
  CSP/origin checks, WebSocket limits, and event-driven complete snapshots.
- Embedded Web UI and native Phone metadata, artwork, transport, seek, rating/Like/Dislike, and
  shuffle behavior.
- Raw Poweramp `bitRate` and `positionInList` values remain unchanged.

## Verification

Development verification completed on 2026-08-13 with the pinned Gradle wrapper `8.14.3`, Microsoft
OpenJDK `21.0.9` (Java 17 source/target), and Android SDK 36.

- Final clean pipeline succeeded:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache --no-daemon`.
- Gradle executed all `100/100` tasks from clean outputs; both debug APKs assembled successfully.
- All `94/94` JVM tests passed across 24 suites with zero failures, errors, or skips:
  Server `66/66` in 15 suites and Phone Client `28/28` in 9 suites.
- Phone lint reports no issues. Server lint reports zero errors and one maintenance-only
  `AndroidGradlePluginVersion` warning because the repository pins wrapper `8.14.3` while `8.14.5`
  is available.
- Phone uses current stable Media3 `1.10.1` `common`/`session`; dependency inspection finds no
  `media3-exoplayer` dependency.
- APK badging confirms Server `versionCode=10`, `versionName=0.9.0` and Phone Client
  `versionCode=10`, `versionName=0.3.0`; both use min API 26 and target/compile API 36.
- Merged manifests confirm Server `RemotePlaybackService` remains non-exported
  `connectedDevice`; Phone `PhoneConnectionService` is the exported Media3 endpoint with
  `connectedDevice|mediaPlayback`; both retain `stopWithTask=false` and required permissions.
- APK Signature Scheme v2 verification passed for both APKs. Both retain the previous Android Debug
  signer certificate SHA-256
  `7112c13a7d98be65d011603fc01855f594b60b7bdd748d814bb3a9630e4f9b42`, preserving install-over-debug
  compatibility.
- Delivery artifacts:
  - `outputs/Poweramp-Remote-Server-v0.9.0-debug.apk` — 124,594 bytes, SHA-256
    `e446b3731894f6cf928fbd86376637c767c5ed6636c87a50cba8699e426b6011`.
  - `outputs/Poweramp-Remote-Phone-v0.3.0-debug.apk` — 3,575,305 bytes, SHA-256
    `411950514dca4dae24b26979d7b1f4dd349828b7fa55df79ebc3a7b0f63c7dd8`.

## Real-device checks required

No hardware pass is claimed by JVM, lint, manifest, dependency, or APK verification. Before release,
use the target HiBy R4 and representative phones/Wear device to confirm:

- Both remote sliders change only the R4/player media volume, display its actual step range, follow
  hardware volume buttons, handle fixed-volume policy, and continue to synchronize screen-off.
- Android notification and lock screen show title/artist/album/artwork, playing/paused, duration and
  advancing position; previous/play-pause/next/seek and remote volume reach Poweramp through Server.
- Compatible Wear OS media controls connect, display current state/artwork, and execute the same
  actions without the Phone producing audio or taking audio focus.
- Starting on shared LAN, removing that Wi-Fi route triggers automatic Wi-Fi Direct discovery and a
  usable API/WebSocket connection without restarting Server or opening Wi-Fi Direct Settings.
- Restored LAN replaces the direct endpoint; DHCP changes, Wi-Fi off/on, P2P channel/group loss, and
  screen lock retain bounded recovery without stale-channel callbacks corrupting the new cycle.
- Android 13+ Nearby devices, Android 8–12L location/Location Mode, mandatory approval, rejection,
  timeout, and unfavorable phone group-owner selection remain user-visible recoverable states.
- Bearer REST, browser session auth, API v1 WebSocket, Web UI, pairing persistence, and upgrade from
  the previous signed debug APKs pass regressions over LAN and P2P.

## Known limitations

- HTTP and `ws://` remain plaintext at the application layer. Do not expose port `8765` to the
  internet.
- Initial pairing remains LAN-only; direct fallback accepts only the previously verified Server ID.
- The direct IPv4 path still requires the player device to become P2P group owner. Android controls
  selection and mandatory approval; the applications do not bypass either.
- System-volume notification behavior and OEM audio policy can vary. No lock is held to override
  vendor power/radio policy; add one only after a reproducible target-device failure proves it needed.
- Force-stop, explicit notification Stop, or reboot ends the corresponding runtime until the app is
  launched again.
- Exact public semantics of Poweramp bitrate units and list index base remain unverified.
- Lyrics remain intentionally out of scope.

## Next scope

Complete the real-device volume, MediaSession/Wear, and LAN → P2P matrix above, then continue the
library/queue, pairing security, and refinement work in [`ROADMAP.md`](ROADMAP.md).
