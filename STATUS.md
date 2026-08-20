# Current project status

Current versions:

- Server: `0.10.1` (`versionCode 12`)
- Phone Client: `0.4.2` (`versionCode 13`)
- API: backward-compatible `v1`

## Stage

The repository builds two native Android applications. Server `0.10.1` and Phone Client `0.4.2` are
focused regression releases for QR request stability, scanner orientation, confirmed-position
timing, and square artwork. They do not add Library, Queue, Lyrics, a new transport, or full
multi-player persistence. The one Server service, one Phone service, LAN/NSD, Wi-Fi Direct,
MediaSession, volume, Web UI, and API `v1` contracts remain in place.

## Implemented in Server 0.10.1 / Phone Client 0.4.2

### Server QR pairing crash

- **Confirmed real-device root cause:** `PairingRequest` compiled its JSON-matching regexp in a
  static field. Android ICU rejected that expression with `PatternSyntaxException`; class
  initialization therefore failed in `PairingRequest.<clinit>`, wrapped the cause in
  `ExceptionInInitializerError`, and killed the `remote-api-client` thread/process before
  `parse()` could perform normal validation. The previous QR request boundary caught only
  `RuntimeException`, so it could neither diagnose nor contain this `Error`. Manual Bearer pairing
  never loads `PairingRequest`, which explains why it continued to work.
- Regex-based JSON parsing has been removed entirely. `PairingRequest.parse()` now uses Android's
  `org.json` object parser, accepts fields in any order plus whitespace/unknown fields, and reads
  the three required values without coercion. `apiVersion` must be an integer equal to `1`;
  `serverId` must be a canonical 22-character Base64URL encoding of 16 bytes; `secret` must be a
  canonical 43-character Base64URL encoding of 32 bytes. Malformed JSON, trailing input, missing
  fields, wrong types, unsupported versions, and invalid encodings become sanitized
  `400 invalid_pairing_request` responses without consuming an active offer. Parser exceptions never
  retain/log the request body or secret; excessively nested input is also contained at this parser
  boundary rather than escaping as `StackOverflowError`.
- The pairing route now contains unexpected runtime failures at its own request boundary, logs the
  full cause/stack without logging request bodies, one-time secrets, or credentials, returns a
  request-local `500 pairing_internal_error`, and leaves the listener/service alive. This remains
  secondary diagnostics; the confirmed static-initializer crash is fixed by deleting the regexp,
  not by broadening the handler to `Throwable`. Reused, expired, ID-mismatched, and
  secret-mismatched offers remain explicit `401` responses logged by non-secret reason.
- `PairingSecretStore` now returns a typed consume result. `ACCEPTED` consumes the active offer;
  `EXPIRED` removes only an expired offer, while wrong version/identity/secret leaves a valid offer
  active.
  The immutable credential response is constructed before atomic consumption so an internal
  serialization failure cannot invalidate a still-usable secret.

### Scanner orientation

- Root cause of forced landscape: JourneyApps 4.3.0 merges its stock `CaptureActivity` with
  `android:screenOrientation="sensorLandscape"`, while Phone explicitly used
  `setOrientationLocked(false)`. The scanner therefore followed the library Activity's landscape
  declaration.
- Phone now launches a dedicated non-exported `QrScannerActivity`. It defaults to
  `userPortrait`; if the calling **Player devices** screen is already configured in landscape, only
  the scanner requests `userLandscape`. MainActivity and PlayerDevicesActivity remain adaptive and
  are not force-rotated.

### Playback-position lag

- **Root cause of the 1–3 second lag:** the WebSocket thread delivered only `RemoteState`; after a
  potentially delayed `Handler.post`, `PhoneConnectionService` treated main-looper callback time as
  the remote position's receipt time. `RemoteSessionPlayer` then independently repeated the same
  late anchoring. Main-thread delay during Activity/scanner transitions therefore became permanent
  position lag until another remote snapshot arrived.
- `RemoteWebSocket` now records monotonic receipt time on its socket thread as soon as the complete
  state frame is available and carries it through `RemoteClientController` unchanged. The service
  combines the confirmed remote position with that original timestamp exactly once. Both the
  player UI and MediaSession consume the same `PlaybackUiSnapshot`; Activity rebind replays the
  original anchor instead of constructing another one.
- Playing snapshots extrapolate to current monotonic time; paused snapshots do not. Pause/resume,
  confirmed seek, track change, and reconnect each establish a fresh received anchor, while
  disconnect freezes the current one without discarding its extrapolated millisecond fraction. No
  REST refresh or playback polling was added.

### Square artwork

- Root cause of vertical stretching: the artwork `FrameLayout` had `match_parent` width and a
  separately weighted height, with no aspect-ratio constraint. Its child then filled two unrelated
  dimensions despite `centerCrop`.
- `SquareArtworkFrameLayout` measures both axes to the smaller available dimension. The existing
  flexible-height budget, rounded outline, placeholder behavior, and `centerCrop` remain, but the
  container and image are always 1:1, including compact and landscape layouts.

### Regression coverage

- Server parser tests cover field-order independence, whitespace, unknown primitive/object/array
  fields, malformed/truncated/non-object/trailing input, every missing field, non-integer and
  unsupported API versions, wrong field types, and invalid/non-canonical Server IDs and secrets.
  HTTP integration proves every malformed/missing/wrong-type request returns `400` without
  consuming the offer, then a reordered request with an unknown nested field succeeds; reuse,
  expiry, wrong secret, method rejection, continued authenticated API availability, and an
  injected unchecked consume failure remain covered.
- Phone request-state tests cover binder-independent cold handoff, one scanner-launch delivery UUID,
  duplicate suppression while pending/active, exact success/failure replay after completion, a new
  launch of the same stale QR, reset after Forget/auth rejection, invalid QR rejection, and
  unchanged manual-token dispatch. Persistence tests recreate `PairingStore`, load QR/manual
  credentials, and verify the restored QR identity selects only the matching discovered reconnect
  target.
- Phone tests prove socket-receipt timestamp propagation, delayed-main-thread extrapolation,
  pause/seek/track-change/disconnect/reconnect anchors, repeated Activity rebind behavior, and the
  portrait-default/landscape-caller scanner policy.

### Phone QR handoff completion

- Scanner output still goes directly to the one existing `PhoneConnectionService` through its
  private start command and service-owned `PairingRequestState`; no Activity binder is consulted.
  The service parses/queues the request, the existing controller performs LAN-first/P2P fallback
  discovery and exchange, `PairingStore.commit()` completes before the credential becomes active,
  and the same controller opens the normal authenticated API/WebSocket connection.
- Each scanner launch now owns a UUID saved with Activity state and carried in the private service
  command. Re-delivery of that UUID while pending/active is idempotently ignored. Previously it
  called `pairQr()` twice: the second call invalidated the first operation generation, so the first
  successful response was discarded and the second POST could receive `401` for the already
  consumed secret. Re-delivery after completion replays the exact service-owned success or failure
  so the Activity cannot remain on false progress.
- A deliberate new scan has a new UUID even when the QR contents are identical, so an old/consumed
  QR still reaches Server and produces the normal understandable stale-code rejection. Forget and
  rejected saved credentials reset delivery history; a delayed old QR delivery cannot replace a
  newer manual-token attempt. Manual Bearer submission itself is never deduplicated.
- QR success remains durably committed before connect. A newly created service/controller loads
  the saved Server ID/service/device/token, restarts existing discovery, accepts only that ID, and
  reconnects through the unchanged LAN/P2P and WebSocket paths. Manual Bearer pairing remains the
  same LAN-only verification and persistence flow.

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

Server `0.10.1` / Phone `0.4.2` development verification completed on 2026-08-21 with pinned Gradle
wrapper `8.14.3`, Temurin JDK `21.0.12` (Java 17 source/target), Android SDK/compile/target 36, and
Build Tools 35.0.0 selected by the pinned Android Gradle Plugin.

- Final requested clean pipeline succeeded:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache --no-daemon --console=plain`.
- Gradle executed all `100/100` tasks from clean outputs and assembled both debug APKs.
- All Server `78/78` JVM tests passed across 18 suites; all Phone `48/48` JVM tests passed across 14
  suites. Both totals have zero failures, errors, or skips.
- Server lint reports zero errors and two informational dependency-version warnings: Gradle
  `8.14.5` and a newer JVM-test-only `org.json` artifact are available. The repository wrapper stays
  pinned at `8.14.3`, and production continues to use Android framework `org.json`. Phone lint
  reports `No issues found` (zero errors and zero warnings).
- APK badging confirms Server `versionCode=12` / `versionName=0.10.1` and Phone
  `versionCode=13` / `versionName=0.4.2`; both use min API 26 and target/compile API 36.
- The merged Server manifest retains one non-exported `RemotePlaybackService` with
  `connectedDevice` and `stopWithTask=false`. The merged Phone manifest retains non-exported
  `PlayerDevicesActivity`, optional camera hardware, and the exported Media3
  `PhoneConnectionService` with `connectedDevice|mediaPlayback` and `stopWithTask=false`.
  `QrScannerActivity` is non-exported and has no static orientation; its runtime policy selects
  portrait/landscape. The library's unused stock capture Activity remains in the merged manifest,
  but scanner options explicitly launch the custom Activity.
- APK Signature Scheme v2 verification passes for both APKs with one Android debug signer,
  certificate SHA-256
  `4d2c7c0d0f8f2b81495d62884a96f361650573d8e99635a6f8cc754e36ec6265`.
- Server delivery artifact: `outputs/Poweramp-Remote-Server-v0.10.1-debug.apk` — 420,288 bytes,
  SHA-256 `f2a8e8452f2b01d3deb9addfec147ac138eb55f05cf106b04c6892fea3e95462`.
- Phone delivery artifact: `outputs/Poweramp-Remote-Phone-v0.4.2-debug.apk` — 5,201,213 bytes,
  SHA-256 `3f60f9d32f0d380b6abaa4ac37433be464b54b34020fcf725f0e28ab7fdad979`.
- Gradle printed its generic Gradle 9 deprecation notice; it produced no lint/build error, and all
  `100` actionable tasks were executed.

### Debug signing handoff

The private key for the previous debug certificate recorded in the `0.9.0/0.3.0` handoff
(`7112c13a…`) was not present in this workspace or the machine's Android/Gradle directories. The
Android plugin generated a new standard local debug keystore for this build. Consequently these
debug APKs cannot install over an APK signed by that previous missing key; doing so would require
restoring the old keystore and rebuilding. Source-level legacy IDs/preferences migration is
preserved, but it can only be exercised across APKs signed by the same available key. This does not
replace a separately managed release signing key.

## Real-device checks required

No hardware pass is claimed by JVM, lint, manifest, dependency, or APK verification; `adb` found no
attached device in this workspace. Before release, use the target player and representative Android
phones/Wear devices to confirm:

- From a cold Phone launch and again immediately after **Pair new player**, scan and pair on shared
  LAN; confirm the Server process remains alive through secret exchange and API/WebSocket startup.
  Repeat with no shared LAN so exact-ID Wi-Fi Direct discovery, system approval, group-owner
  selection, secret exchange, and API/WebSocket startup complete.
- On a device without a camera (or with camera denied), enter the copied Bearer token manually;
  verify LAN discovery, invalid-token feedback, persistence, Re-pair, and retention of the previous
  association after failure.
- Successful, repeated, expired, replaced, malformed, wrong-device, and wrong-secret pairing HTTP
  requests must never terminate the Server. Inspect the new sanitized Server log for the typed
  outcome/stack; wrong requests must not consume a still-valid offer. Failed Pair new/Re-pair
  retains a previously working association; Forget removes it locally.
- Android 13+ Nearby devices, Android 8–12L location/Location Mode, camera denial/permanent denial,
  Wi-Fi off, P2P unsupported, approval rejection/timeout, and phone-as-group-owner remain recoverable.
- With music playing, background/recreate/reopen the Phone Activity after a long interval: seekbar
  and elapsed time immediately match Poweramp and the continuing notification/MediaSession without
  the former fixed 1–3 second lag. Repeat paused, after resume, seek, track change, reconnect, a
  delayed snapshot, and configuration change.
- Typical and short phone screens have no main-player vertical scroll or clipped required controls;
  test status-bar/cutout and gesture/three-button navigation insets, confirm volume is always visible,
  and exercise landscape/anomalously small windows without crashes. Confirm the artwork's measured
  width and height remain equal, with no image stretching. Artwork rounding/crop, chips, slider
  accuracy, selected states, ripple, haptics, touch targets, and accessibility descriptions must
  remain acceptable.
- Launch the scanner from a portrait Player devices screen and confirm scanner-only portrait; then
  launch it from an explicitly landscape screen and confirm scanner-only landscape. Returning from
  either path must not force the rest of Phone Client into that orientation.
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

Complete the Server `0.10.1` / Phone `0.4.2` retained regression hardware matrix above. Then
continue multi-player foundation, pairing/transport security, Library, Queue, and Lyrics only as
separately scoped work in [`ROADMAP.md`](ROADMAP.md).
