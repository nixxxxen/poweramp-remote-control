# Current project status

Current versions:

- Server: `0.10.0` (`versionCode 11`)
- Phone Client: `0.4.1` (`versionCode 12`)
- API: backward-compatible `v1`

## Stage

The repository builds two native Android applications. `:app` remains the unchanged `0.10.0`
foreground Server on the Poweramp/player device; `:phone` remains the one foreground native remote
client. Phone `0.4.1` is a focused regression release for QR lifecycle handoff, manual pairing
fallback, safe insets, seek styling, and compact player fit. It does not add Library, Queue, Lyrics,
a new transport, or full multi-player persistence; API remains `v1`.

## Implemented in Phone Client 0.4.1

### QR lifecycle and manual fallback

- Root cause of **«Фоновый сервис соединения недоступен»**: opening the scanner stops
  `PlayerDevicesActivity`; `onStop()` removes its listener, unbinds, and clears `LocalBinder`. The
  Activity Result callback can run after return but before asynchronous `onServiceConnected()`, so
  the old callback rejected a valid QR solely because `controller == null` at that instant.
- QR contents now enter the already existing `PhoneConnectionService` through a private explicit
  foreground-service command protected by the process-internal token. `PairingRequestState` keeps
  the validated request until the single `RemoteClientController` can consume it; neither cold
  launch nor scanner return depends on Activity binding timing. Pairing failures are retained by the
  service until `PlayerDevicesActivity` consumes them after rebind.
- **Player devices** now always presents **Scan QR code** and **Enter token manually**. **Re-pair**
  offers the same choice. Scanner launch proactively starts the same service, while the result
  command also starts it idempotently.
- Manual fallback trims and validates the existing canonical 43-character Bearer token, searches
  ordinary LAN NSD candidates, verifies each through unchanged authenticated
  `GET /api/v1/state`, and persists the verified stable identity/name/token through `PairingStore`.
  It accepts no address and adds no second connection architecture. QR exact-ID LAN/P2P behavior is
  unchanged; initial manual pairing is LAN-only because a bare token contains no Server identity.

### Insets and compact player fit

- Root cause of top/bottom overlap: Phone targets API 36, where edge-to-edge is enforced on recent
  Android, but both View/XML roots had only fixed padding and never applied system-bar or display-
  cutout insets. Both Activities now opt into one consistent edge-to-edge path on all supported
  versions and add the union of system bars and display cutout to each root's original padding.
  Insets are recalculated by the platform and are applied once; there is no Scaffold or nested
  inset consumer to double them.
- Root cause of the missing/clipped volume control: the weighted artwork also imposed a `180dp`
  minimum, so required fixed controls could exceed compact safe height. Artwork is now the flexible
  remainder, while top chrome, metadata, seek/time, volume, primary transport, and secondary
  controls form the fixed no-scroll budget. The volume row stays present even before a remote
  volume snapshot, in a disabled placeholder state.
- Vertical gaps and control sizes were tightened without dropping text below `11sp`. Normal compact
  portrait height gives remaining space to artwork; at landscape or anomalously small height the
  weighted artwork can collapse rather than causing a measurement exception. No main-player
  `ScrollView` was added.
- Playback seek uses the existing platform `SeekBar` gesture/accuracy behavior with an `8dp` rounded
  track (previously `4dp`) and a smaller `14dp` thumb. Volume retains the same exact integer API
  semantics with a slightly clearer `4dp` track.

### Regression coverage

- `PairingRequestStateTest` proves a QR request remains pending with no bound/available target and
  dispatches exactly once when the service runtime becomes available; it covers manual-token use of
  the same handoff.
- `PlaybackUiSnapshotTest` now also proves repeated Activity rebind captures continue extrapolating
  from the original service anchor instead of re-anchoring the stale raw position.

## Implemented in Server 0.10.0 / Phone Client 0.4.0

### QR pairing

- Server owns one active 256-bit random pairing secret with a two-minute monotonic lifetime. A new
  offer replaces the previous offer; successful exchange atomically invalidates it.
- Server renders `powerampremote://pair` as QR. The strict payload contains API version `1`, the
  persistent public Server ID, a non-secret player-device name, and the one-time secret. It contains
  neither the persistent Bearer credential nor a LAN/P2P address.
- Phone **Pair new player** and **Re-pair** use an embedded QR-only scanner with the Activity Result
  API. Camera hardware remains optional in the manifest.
- A scanned offer selects only its exact Server ID. Existing LAN NSD is tried first; after the same
  grace period, existing pre-association Wi-Fi Direct DNS-SD searches for that identity. Runtime
  permission, Location Mode, Wi-Fi state, Android/OEM approval, and group-owner decisions are still
  system-controlled and recoverable in UI.
- Additive unauthenticated `POST /api/v1/pair` accepts only the active secret/ID/API tuple and returns
  the existing persistent API v1 Bearer credential. Expired, reused, replaced, wrong-ID, and wrong
  version offers fail. Responses use `Cache-Control: no-store`.
- Phone persists credentials only after successful exchange. Re-pair does not replace the previous
  association until then; a rejected QR secret is distinct from rejection of a saved credential.
- Existing `0.3.0` saved Server ID/service/token preferences migrate in place, using the old service
  name as the initial device label. No resolved endpoint is persisted.
- The Server no longer displays the persistent credential. An explicit sensitive-clipboard action
  remained for the unchanged Web UI/external API v1 login flow. Phone `0.4.0` shipped without a
  manual token field; `0.4.1` restores it as the camera-free fallback documented above.

### Resume/rebind playback restoration

- `PhoneConnectionService` now owns `PlaybackUiSnapshot` alongside its MediaSession state. Each
  complete WebSocket state establishes a monotonic playback anchor; disconnect freezes it.
- A newly added Activity listener receives the cached complete state followed immediately by a
  position extrapolated from the original service anchor. Recreated UI therefore agrees with the
  foreground notification instead of re-anchoring a stale raw position at zero/current time.
- The player retains its short seek-confirmation window, so an older snapshot cannot jump under the
  user's finger. No REST refresh, network polling, or elapsed-second state polling was added.
- Pure tests cover rebind extrapolation from zero, playing duration clamp, pause, and disconnect
  freeze behavior.

### Player devices and Phone player UI

- Pairing/connection management moved to a separate generic **Player devices** screen. It shows the
  saved player-device name, Connected/Disconnected, current LAN/Wi-Fi Direct transport, NSD service,
  API version, public Server ID, transient endpoint, runtime status, Pair new player, Re-pair, and
  Forget device.
- The presentation model is device-neutral and ready to become a collection, while persistence and
  active connection intentionally remain one-slot in this release.
- Wi-Fi Direct permission/settings/retry actions moved off the player and retain the one-request then
  app-settings behavior for permanently denied permissions.
- The main Phone screen contains playback only and has no vertical `ScrollView`, product heading,
  native-client caption, transport/API diagnostics, manual pairing, or reset action.
- Flexible rounded artwork receives the released space. Previous/Play-Pause/Next remain large;
  rating, Like, Dislike, and Shuffle are compact, with selected states, platform ripple/pressed
  feedback, and haptics.
- Seek and volume use dedicated player-style tracks/thumbs while retaining exact integer control.
  Codec/file type, bit depth, sample rate, and bitrate are separate muted metadata chips.
- Metadata, artwork, transport, seek, rating `0…5`, Like/Dislike, shuffle, player-device volume,
  MediaSession, notification/lock-screen, and Wear control paths are retained.

## Preserved behavior

- One Server foreground service and one Poweramp integration path; one Phone foreground service.
- LAN-first `_poweramp-remote._tcp.` discovery, stable-identity Wi-Fi Direct fallback, automatic
  reconnect, and mandatory system approval/group-owner decisions.
- Port `8765`, all previous API v1 routes/fields/commands/status semantics, protected-route Bearer
  auth, browser session auth, CSP/origin checks, WebSocket limits, and event-driven complete state.
- Embedded Web UI and its artwork, transport, seek, rating/Like/Dislike, shuffle, and remote volume.
- Raw Poweramp `bitRate` and `positionInList` values remain unchanged.

## Verification

Phone `0.4.1` development verification completed on 2026-08-15 with pinned Gradle wrapper
`8.14.3`, Temurin JDK `21.0.12` (Java 17 source/target), Android SDK/compile/target 36, and Build
Tools 36.0.0.

- Final requested clean pipeline succeeded:
  `clean :phone:testDebugUnitTest :phone:lintDebug :phone:assembleDebug --no-build-cache
  --no-daemon --console=plain`.
- Gradle executed all `51/51` tasks from clean outputs and assembled the Phone debug APK.
- All Phone `36/36` JVM tests passed across 12 suites with zero failures, errors, or skips. This is
  the prior 33-test baseline plus two service-request handoff cases and one repeated-rebind case.
- Phone lint reports `No issues found` (zero errors and zero warnings).
- APK badging confirms `versionCode=12`, `versionName=0.4.1`, min API 26, and target/compile API 36.
- The merged manifest keeps non-exported `PlayerDevicesActivity`, optional camera hardware, and the
  one exported Media3 `PhoneConnectionService` with `connectedDevice|mediaPlayback` and
  `stopWithTask=false`.
- APK Signature Scheme v2 verification passes with one Android debug signer, certificate SHA-256
  `4d2c7c0d0f8f2b81495d62884a96f361650573d8e99635a6f8cc754e36ec6265`.
- Delivery artifact: `outputs/Poweramp-Remote-Phone-v0.4.1-debug.apk` — 5,197,753 bytes, SHA-256
  `7e23a4d2856a801368d779e4a33ffdd889f6768fa8e83abad1ed95bfa71a0e91`.
- No `:app` source or Server version changed. The previously verified Server `0.10.0` delivery
  artifact remains `outputs/Poweramp-Remote-Server-v0.10.0-debug.apk` (419,172 bytes, SHA-256
  `e16801b78e99acc86449a29b993181584bc05245a763c9e013506fb0eaaa409c`); its pipeline was not rerun
  for this Phone-only regression release.

### Debug signing handoff

The private key for the previous debug certificate recorded in the `0.9.0/0.3.0` handoff
(`7112c13a…`) was not present in this workspace or the machine's Android/Gradle directories. The
Android plugin generated a new standard local debug keystore for this build. Consequently these
debug APKs cannot install over an APK signed by that previous missing key; doing so would require
restoring the old keystore and rebuilding. Source-level legacy IDs/preferences migration is
preserved, but it can only be exercised across APKs signed by the same available key. This does not
replace a separately managed release signing key.

## Real-device checks required

No hardware pass is claimed by JVM, lint, manifest, dependency, or APK verification. Before release,
use the target player and representative Android phones/Wear devices to confirm:

- From a cold Phone launch and again immediately after **Pair new player**, scan and pair on shared
  LAN; repeat with no shared LAN so exact-ID Wi-Fi Direct discovery,
  system approval, group-owner selection, secret exchange, and API/WebSocket startup complete.
- On a device without a camera (or with camera denied), enter the copied Bearer token manually;
  verify LAN discovery, invalid-token feedback, persistence, Re-pair, and retention of the previous
  association after failure.
- Expired, reused, replaced, malformed, wrong-device, and canceled QR flows are rejected cleanly;
  failed Pair new/Re-pair retains a previously working association; Forget removes it locally.
- Android 13+ Nearby devices, Android 8–12L location/Location Mode, camera denial/permanent denial,
  Wi-Fi off, P2P unsupported, approval rejection/timeout, and phone-as-group-owner remain recoverable.
- With music playing, background/recreate/reopen the Phone Activity after a long interval: seekbar
  and elapsed time immediately match the continuing notification/MediaSession. Repeat paused,
  after seek, after track change, after reconnect, and across configuration change.
- Typical and short phone screens have no main-player vertical scroll or clipped required controls;
  test status-bar/cutout and gesture/three-button navigation insets, confirm volume is always visible,
  and exercise landscape/anomalously small windows without crashes. Artwork rounding/crop, chips,
  slider accuracy, selected states, ripple, haptics, touch targets, and accessibility descriptions
  must remain acceptable.
- All prior metadata/artwork, Previous/Play-Pause/Next, seek, rating/Like/Dislike, shuffle,
  player-device volume, LAN preference/recovery, Web UI/browser sessions, notification/lock-screen,
  and compatible Wear OS behavior pass regressions over LAN and P2P.
- Restore the prior debug keystore if install-over-debug migration from the previously delivered
  `0.9.0/0.3.0` APKs must be tested; otherwise test migration with consistently release-signed APKs.

## Known limitations

- HTTP and `ws://` remain plaintext at the application layer. A one-time secret reduces QR exposure
  but does not protect the exchange from an observer on an untrusted LAN. Never expose port `8765`
  to the internet.
- Only one QR offer and one saved/active player slot are supported. Full multi-player management and
  paired-client revocation are future work.
- Initial manual Bearer-token pairing is LAN-only because the token carries no stable Server ID;
  address-free QR remains the initial LAN/Wi-Fi Direct pairing route.
- The direct IPv4 path still requires the player device to become P2P group owner. Android controls
  selection and mandatory approval; the applications do not bypass either.
- Force-stop, explicit notification Stop, or reboot ends the corresponding runtime until launch.
- Exact public semantics of Poweramp bitrate units and list index base remain unverified.
- Library, Queue, and Lyrics remain intentionally out of scope.

## Next scope

Complete the `0.4.1` QR/manual/insets/UI and retained regression hardware matrix above. Then
continue multi-player foundation, pairing/transport security, Library, Queue, and Lyrics only as
separately scoped work in [`ROADMAP.md`](ROADMAP.md).
