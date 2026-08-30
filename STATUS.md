# Current project status

Current versions:

- Server: `0.10.2` (`versionCode 13`)
- Phone Client: `0.5.0` (`versionCode 14`)
- API: backward-compatible `v1`

## Stage

The repository builds two native Android applications. Server `0.10.2` and Phone Client `0.5.0`
form the release-validated second-public-release baseline for complete Phone English/Russian
localization, a minimal Phone navigation menu, Settings/app-language selection, About, and an
English-only Server/Web UI. They do not add Library, Queue, Lyrics, a new transport, or full
multi-player persistence. The one Server service, one Phone service, LAN/NSD, Wi-Fi Direct,
pairing/reconnect path, MediaSession, volume, Web UI, and API `v1` contracts remain in place.

The complete signed release pipeline, APK/certificate verification, and exact-APK in-place
real-device matrix passed on 2026-08-23. The immutable checked APKs were built and tagged from
commit `675d1affd8776bb6b05dfa1795df51a18de08fcc`; their public release remains the upgrade baseline
for the unversioned post-release work below.

Post-release development now contains only the first requested Phone UI refinement: a unified
dynamic artwork theme on the existing View-based main player. Versions intentionally remain Server
`0.10.2` / code 13 and Phone `0.5.0` / code 14; API remains `v1`. This working candidate does not
change Server, Web UI, either service, connection/playback state, transport, or any other planned UI
package. Automated verification is recorded below; physical-device visual/performance validation
remains required before treating this post-release change as release-ready.

## Current post-release Phone implementation: dynamic artwork theme

- `RemoteState.artworkKey()` remains the stable artwork identity and is combined in memory with the
  paired public Server ID. Revision, playback-position, Activity, and transient endpoint changes do
  not alter the key. A process-local access-ordered LRU holds at most 12 palettes and never retains a
  full-size bitmap; concurrent requests for one key share one analysis. The single analysis worker
  also has a bounded three-item pending queue.
- The already decoded artwork is sampled on that worker at no more than 40 × 40 points. Transparent,
  near-black, near-white, and low-chroma pixels are rejected; remaining colors are grouped
  deterministically by hue/saturation/lightness, ranked by prevalence and usable chroma, checked for
  perceptual separation, and normalized to moderate saturation and dark-interface lightness. Fewer
  than two useful colors, absent artwork, decode failure, or an unusable image selects the calm
  built-in blue/violet/green fallback.
- A dedicated `ArtworkThemeBackgroundView` exists only behind `MainActivity` content. It draws a
  fixed dark base, artwork gradient, three oversized soft radial-gradient fields, and a permanent
  contrast scrim. Shaders are rebuilt only for size/palette changes; each motion frame changes only
  canvas positions and alpha, with no bitmap blur, bitmap allocation, network request, or shader/
  Drawable construction in `onDraw()`.
- Main presentation state owns a separate request generation. A result applies only when both its
  generation and Server/artwork cache key are still current. Metadata may arrive first without
  resetting the background; a missing/decode-failed image resolves smoothly to fallback after a
  short grace period. A later valid palette then replaces it normally.
- A track change cross-fades the full old gradient/forms into the new set. If another change arrives
  mid-transition, the next transition begins from the current interpolated visual state, cancels the
  old animator, and never builds overlapping animator chains. Activity saved state retains current/
  target palettes and motion phase across configuration recreation; process cache covers ordinary
  stop/start and rebind without repeat analysis.
- Palette and motion animators are bound to `MainActivity` visibility. Motion stops at `onStop()` and
  resumes from its retained phase. Android's disabled animator scale immediately selects the final
  palette and starts no continuous movement. Existing controls, haptics, descriptions, selected
  states, chip/icon colors, player geometry, safe insets, square artwork, and always-visible volume
  remain unchanged.

## Implemented in Server 0.10.2 / Phone Client 0.5.0

### Phone navigation, Settings, and About

- The compact, non-scrolling main player has a left 40 dp three-line menu button symmetric with
  the existing right **Player devices** button. It retains the same visual treatment, haptic style,
  safe-inset handling, square artwork budget, and always-visible volume control.
- A native `PopupMenu` opens **Settings** and **About** without adding AppCompat, a navigation
  framework, or another runtime.
- Settings and About are presentation-only Activities using the existing theme, edge-to-edge safe
  drawing insets, normal Back behavior, and haptic feedback. Neither owns, stops, or recreates the
  connection runtime.
- About reads `versionName` and `versionCode` from `PackageManager`, keeps `buildConfig = false`,
  shows local API v1, repository/license/notices/independence information, and opens public links
  with ordinary `ACTION_VIEW` intents.

### Phone application language

- The stable private language tags are `system`, `ru`, and `en`, stored in a preferences file
  separate from `PairingStore`; changing language cannot remove the saved Server ID or Bearer
  credential.
- **System default** resolves Russian only when the primary system locale is Russian and English
  for every other locale. Android 13+ uses platform `LocaleManager`; API 26–32 wraps each
  presentation context with a small configuration override. Protocol parsing and normalization
  continue to use their existing `Locale.ROOT` paths.
- The selected locale applies immediately. Only presentation Activities may recreate on API
  26–32; the existing `PhoneConnectionService` remains alive and rebuilds its notification/channel
  text from the new locale.
- `localeConfig` advertises only `en` and `ru`, and language splitting is disabled so both supported
  resources remain installed. Minimum API remains 26.

### Complete Phone localization

- `values/strings.xml` is the complete English fallback and `values-ru/strings.xml` contains the
  matching complete Russian translation. Their translatable keys, arrays, item counts, and format
  placeholders are parity-tested; the English fallback is also checked for Cyrillic user text.
- Main, Player devices, Settings, About, QR prompts, dialogs, Toast/error/status messages, buttons,
  hints, runtime diagnostics, accessibility descriptions, foreground notification/actions, and
  notification channel are resource-driven in both languages.
- Metadata presentation uses locale-aware resources: English `bit`, `kHz`, `MHz`, `kbps` with a
  decimal point; Russian `бит`, `кГц`, `МГц`, `кбит/с` with a decimal comma. The tolerant bitrate
  policy and raw, unoffset `positionInList` semantics remain unchanged.

### English-only Server and embedded Web UI

- Server Activity, pairing instructions, controls, states, errors, accessibility descriptions,
  foreground notification/actions, and notification channel are English.
- The embedded Web UI declares `<html lang="en">`; login, metadata, playback, seek, volume,
  rating, Like/Dislike, shuffle, connection/error messages, and ARIA labels are English and tested
  for absence of Cyrillic presentation text.
- API v1 remains byte-compatible. Its historical Russian `sourceCategoryName` field is isolated as
  protocol data and preserved exactly; native Phone presentation and the English Web UI derive
  display labels from the unchanged numeric `sourceCategory` value instead.

### Upgrade and architecture compatibility

- Server is `0.10.2` / code 13 and Phone is `0.5.0` / code 14. Application IDs remain
  `dev.r4remote.poweramp` and `dev.r4remote.poweramp.phone`; API remains v1.
- Exact release-signed device validation confirms these APKs update public `0.10.1` / `0.4.2` in
  place with the same permanent release certificate, preserving Server identity/token and Phone
  identity/credential without requiring re-pairing.
- There is still one Server foreground service/Poweramp path and one Phone connection/MediaSession
  service. No cloud, polling, manual IP entry, analytics, updater, duplicate transport, or protocol
  fork was added.

## First-public-release preparation (historical, 2026-08-22)

- A protected full mirror backup of the original private history was created outside the
  repository before any rewrite and is being retained. It contains the old commits and local refs
  but has no reason to be pushed or published.
- The controlled rewrite preserved all 12 existing commits, their order, messages, author names,
  and dates. Commit SHA values changed because commit metadata and some historical trees changed.
  The personal Gmail address was replaced in all author/committer metadata by the configured GitHub
  noreply address. Historical `build-seek-verification` trees, `.DS_Store`, compiled/test outputs,
  local paths, hostname, and build timestamps from those generated trees were removed. Reachable
  blob count fell from 527 to 413 and tree count from 322 to 242; the current source tree itself was
  unchanged by the rewrite.
- Post-rewrite `git log --format=fuller`, full object scans, and strict object validation found only
  the noreply author/committer email and no old Gmail, forbidden generated path, absolute local
  path, private key, keystore, credential, API token, APK/AAB, environment file, or signing
  properties. Deterministic Base64URL values that remain are test fixtures and protocol constants.
- Only rewritten `main` was force-pushed; no `--mirror`, local `refs/codex/*`, or tags were pushed.
  A separate fresh clone matched rewritten local and remote `main`, contained only the expected
  branch refs, retained the same 12-commit linear structure, passed strict object validation, and
  repeated the same PII/secret/generated-file scan with no finding.
- Both Android modules now load one permanent release identity from an external properties file and
  keystore outside the repository. Missing or incomplete configuration blocks any scheduled
  release task before execution, including aggregate builds; debug builds remain separately
  debug-signed. The release certificate is `CN=Poweramp Remote Release`, RSA 4096, valid through
  2054-01-07, with SHA-256 fingerprint
  `C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.
  No keystore path, key material, or password is tracked.
- The first permanent release identity differs from every pre-release debug identity. Because
  Android requires an update to carry the same signing certificate, the first public build is an
  explicit fresh-install boundary: old debug Server and Phone apps must be uninstalled, their local
  pairing state is removed, and pairing must be completed again.
- The resolved release runtime graphs were re-audited against `THIRD_PARTY_NOTICES.md`. The MIT
  license, Poweramp API-derived notice, Apache/AndroidX/Media3/JourneyApps/ZXing/Kotlin/coroutines/
  Guava/JSpecify/JetBrains attributions, Kotlin BSD/Boost notices, and development-only dependency
  terms remain covered. `PowerampContract.java` retains its required upstream attribution.

The signing-recovery gates are complete: protected keystore copies exist on an external medium and
a separate access-controlled computer, and the alias plus both passwords are stored separately in
a password manager. The full fresh-install real-device matrix in `RELEASING.md` also passed as
recorded below. Repository visibility, tag creation, and GitHub Release creation remain deliberately
manual actions.

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

### Post-release dynamic-artwork-theme automation (2026-08-30)

- One clean no-build-cache debug pipeline completed through pinned Gradle Wrapper `8.14.3` with
  Temurin JDK 21, Java 17 source/target, Android compile/target 36, and Build Tools 35.0.0:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug`. All `100/100` actionable tasks executed from clean
  outputs.
- Server passed its unchanged `78/78` JVM tests; Phone passed `70/70` tests, with zero failures,
  errors, or skips. New pure tests cover deterministic palette selection, absent/monochrome
  fallback, bright-color normalization, Server-aware stable cache keys, bounded LRU eviction,
  same-key in-flight coalescing/cache reuse, rejected-queue cleanup, current-generation acceptance,
  rejection of two late rapid-track results, exact interpolation endpoints, mid-transition
  retargeting, and immediate final state when animations are disabled. Existing pairing, network,
  API, playback, formatting,
  localization, MediaSession, and Server suites remain present and passing.
- Phone lint reports `No issues found` (zero errors and zero warnings). Server lint has zero errors
  and only its two existing dependency-update notices for pinned Gradle `8.14.3` and the
  JVM-test-only `org.json` artifact; no lint rule was disabled or weakened.
- Actual debug APK badging confirms unchanged Server package `dev.r4remote.poweramp`, code 13/name
  0.10.2, and Phone package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26
  and target/compile API 36.
- The merged manifests still contain exactly one non-exported Server
  `RemotePlaybackService(connectedDevice)` and one exported Media3 Phone
  `PhoneConnectionService(connectedDevice|mediaPlayback)`, both with `stopWithTask=false`. Phone
  retains its locale config and the same Main, Player devices, Settings, About, and scanner
  Activities; no service, permission, provider, receiver, or application ID was added by the theme.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  Android debug signer. This task deliberately did not build or publish release artifacts.
- `git diff --check` passes. Server source, Web UI, API, Gradle dependency declarations, versions,
  signing configuration, and release history are unchanged.

### Dynamic artwork theme real-device checks still required

Do not mark this unversioned post-release change release-ready until the following matrix passes on
physical Phone devices, preferably including API 26–32 and Android 13+:

- Play tracks with colorful, very bright, dark, low-saturation, nearly monochrome, missing, and
  malformed/unavailable artwork. Confirm palettes remain characteristic but dark, fallback is calm,
  and title/artist/album, chips, icons, seek, volume, and error text retain clear contrast.
- Confirm metadata-first artwork loading keeps the previous visual palette without a black flash,
  then transitions once; confirm unavailable artwork settles into fallback after the grace period.
- Press Next/Previous rapidly across several tracks and exercise natural automatic changes. No late
  palette may recolor the current track, no transition may jump backward, and animator chains must
  not accumulate.
- Background/reopen Main, open and return from Player devices/Settings/About, rotate/recreate, and
  reconnect over LAN and Wi-Fi Direct. Same artwork should not re-analyze or flash. Then restart the
  Phone process: one fresh process-local analysis may occur, but startup must remain calm and the
  connection, pairing, playback anchor, and MediaSession must remain intact.
- Check compact portrait, short-height, landscape, cutout, gesture-navigation, and three-button
  navigation layouts. The player must remain non-scrolling, artwork square, volume visible, touch
  targets/haptics/selected states unchanged, and the dynamic layer absent from every non-Main screen.
- Set the system animator duration scale to off before launch and while Main is visible. The final
  palette must apply immediately with no palette tween or perpetual form motion; restoring animator
  scale and restarting visibility should restore the slow motion without a flash.
- Profile ordinary playback, single and rapid track changes, return-to-Activity, and landscape for
  frame pacing, CPU, and memory. Confirm no noticeable frame drops, sustained background work while
  Main is hidden, growing bitmap retention, or additional artwork/network requests.
- Repeat core playback regression: artwork, metadata, seek/elapsed time, volume, Previous,
  Play/Pause, Next, rating, Like/Dislike, Shuffle, notification/lock-screen/Wear controls, screen-off,
  and reconnect over LAN plus Wi-Fi Direct fallback.

### Second-public-release RC automation (2026-08-22)

- A clean no-build-cache debug pipeline completed successfully through the pinned Gradle Wrapper:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug`. All `100/100` actionable tasks executed.
- Server passed `78/78` debug JVM tests across 18 suites; Phone passed `57/57` across 16 suites.
  All 135 executions have zero failures, errors, or skips.
- Debug lint reports zero errors. Phone reports no issues; Server retains only the two existing
  dependency/update warnings for the pinned Gradle Wrapper and JVM-test-only `org.json`.
- Debug APK badging confirms Server package `dev.r4remote.poweramp`, code 13/name 0.10.2, and Phone
  package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26 and target/compile
  API 36.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  Android debug signer. The merged manifests retain exactly one non-exported Server service and one
  exported MediaSession-capable Phone service; Player devices, Settings, About, and the custom QR
  scanner are all non-exported.
- Focused tests cover stable language tags and ru/en resolution, private preference persistence,
  English/Russian resource/array/placeholder parity, absence of Cyrillic English resource text,
  localeConfig, locale-aware metadata units/separators/bitrate/raw list position, English Web UI
  language/copy/ARIA, absence of Cyrillic Web UI text, and the historical API v1 category-name
  payload.
- The initially unavailable external signing-properties path was then supplied without exposing
  its contents. No keystore path, alias, password, or private-key material entered source, command
  output, Git, or public release files.

### Second-public-release signed verification (2026-08-23)

- The pinned Gradle Wrapper ran `clean`, both release unit-test tasks, both release lint tasks, and
  both release assembly tasks with the external permanent signing configuration. The build
  completed successfully: 110 actionable tasks, 107 executed and 3 up-to-date.
- Server passed `78/78` release JVM tests across 18 suites; Phone passed `57/57` across 16 suites.
  All 135 executions had zero failures, errors, or skips.
- Release lint reports zero errors. Phone reports no issues; Server reports only the two existing
  informational update warnings for pinned Gradle and the JVM-test-only `org.json` dependency.
- Release APK badging confirms Server `dev.r4remote.poweramp`, code 13/name 0.10.2, and Phone
  `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26 and target/compile API
  36 and pass 16 KiB-aware ZIP alignment.
- Both APKs verify with APK Signature Scheme v2 and exactly one non-debug signer:
  `CN=Poweramp Remote Release`, RSA 4096, certificate SHA-256
  `C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.
  That fingerprint exactly matches both first-public-release APKs, proving Android update-signature
  continuity.
- Both APKs embed source revision `675d1affd8776bb6b05dfa1795df51a18de08fcc`. Final file hashes:
  - `Poweramp-Remote-Server-v0.10.2.apk`:
    `aeafbf075d45b41e9c311bdbd19a3c473df9d6156540a9f7c6c60b05c52e52ba`;
  - `Poweramp-Remote-Phone-v0.5.0.apk`:
    `6d69abfc1a38ebf74751b08f1dc9fed9e576d39f5528450bc6558ff1b60c71d5`.
- `SHA256SUMS.txt` verifies both final-named APK copies. The release bundle also contains `LICENSE`,
  `THIRD_PARTY_NOTICES.md`, and `Apache-2.0.txt`.
- Because Android Gradle Plugin embeds the VCS revision, the release tag for these exact checked
  binaries must point to `675d1affd8776bb6b05dfa1795df51a18de08fcc`. This later validation-only
  documentation commit is deliberately not the APK source commit and does not require rebuilding
  the already checked binaries.

### Second-public-release real-device validation (passed 2026-08-23)

The maintainer confirmed every item below using the exact final release-signed APKs listed above.
Server ran on a Hiby R4 with Android 12; Phone Client ran on a Samsung Galaxy S24 Ultra with Android
16.

- [x] Install Server `0.10.2` over public Server `0.10.1` without uninstalling it.
- [x] Confirm Server identity, API token, browser/API access, and pairing state remain intact.
- [x] Install Phone `0.5.0` over public Phone `0.4.2` without uninstalling it.
- [x] Confirm the saved Server identity and Bearer credential remain intact and reconnect without
  re-pairing.
- [x] Check first launch in **System default**.
- [x] Switch **Russian → English → Russian** and confirm immediate presentation changes.
- [x] Check Main, Player devices, Settings, About, dialogs, scanner, notification actions,
  foreground notification text, and notification-channel copy in both languages.
- [x] Restart Activities, both applications, and both devices; confirm language selection and
  pairing survive every restart.
- [x] Check the Server Activity, foreground notification/channel, and embedded Web UI for
  English-only presentation.
- [x] Repeat both QR pairing and manual Bearer-token pairing.
- [x] Repeat LAN/NSD operation and Wi-Fi Direct fallback, including recovery back to preferred LAN.
- [x] Repeat background/screen-off operation, artwork, all metadata, playback, seek, rating,
  Like/Dislike, shuffle, player-device volume, MediaSession, lock screen, compatible Wear OS, and
  reconnect behavior.

### First-public-release candidate verification (historical, 2026-08-22)

- One clean no-build-cache pipeline ran debug and release unit tests, lint, and APK assembly for
  both modules with the external signing configuration. It completed successfully with 206
  actionable tasks (203 executed, three profile inputs already up to date).
- Server passed `78/78` debug and `78/78` release JVM tests; Phone passed `48/48` debug and `48/48`
  release JVM tests. All 252 executions have zero failures, errors, or skips.
- Debug and release lint both report zero errors. Phone has no issues; Server has only the same two
  informational update warnings for pinned Gradle `8.14.3` and JVM-test-only `org.json`.
- Missing signing configuration was exercised separately: debug assembly and dependency reporting
  still work, while direct `assembleRelease` and aggregate `assemble` both stop before task
  execution. No signing secret is present in Gradle source, command output, or the repository.
- Final Server APK: `Poweramp-Remote-Server-v0.10.1.apk`, application ID
  `dev.r4remote.poweramp`, `versionCode=12`, `versionName=0.10.1`, min API 26, and target/compile
  API 36.
- Final Phone APK: `Poweramp-Remote-Phone-v0.4.2.apk`, application ID
  `dev.r4remote.poweramp.phone`, `versionCode=13`, `versionName=0.4.2`, min API 26, and
  target/compile API 36.
- Both APKs pass 16 KiB-aware ZIP alignment and `apksigner verify`; each has one RSA 4096 signer and
  APK Signature Scheme v2. V1, v3/v3.1, v4, and SourceStamp are absent; v2 supports every targeted
  device because the project minimum is API 26. The signer fingerprint matches the permanent
  release certificate documented above and differs from the Android Debug certificate SHA-256
  `4D:2C:7C:0D:0F:8F:2B:81:49:5D:62:88:4A:96:F3:61:65:05:73:D8:E9:96:35:A6:F8:CC:75:4E:36:EC:62:65`.
- `SHA256SUMS.txt`, the MIT `LICENSE`, `THIRD_PARTY_NOTICES.md`, and the Apache 2.0 license copy are
  prepared beside the two ignored local APK artifacts for later manual upload. Exact APK hashes are
  intentionally generated after the final source commit and recorded in that untracked checksum
  file and the GitHub Release description: Android Gradle Plugin embeds the source commit revision,
  so a checksum cannot be self-consistently stored in the commit that produces the APK.

### Release-candidate device validation (2026-08-22)

The exact release candidates built from commit `c7e4ae1` completed the mandatory 16-step
fresh-install matrix without a failure:

- Server `0.10.1` ran on a Hiby R4 with Android 12; Phone Client `0.4.2` ran on a Samsung Galaxy
  S24 Ultra with Android 16.
- Both prior debug apps were uninstalled before the release-signed APKs were installed, confirming
  the documented fresh-install boundary and required re-pairing.
- QR pairing and manual Bearer fallback, LAN/NSD and automatic LAN-to-Wi-Fi Direct fallback,
  background/screen-off operation, metadata/artwork, transport and seek, Like/Dislike/Shuffle,
  remote volume, MediaSession notification/lock-screen controls, compatible Wear OS controls, and
  reconnect after application/device restarts all passed.
- Tested Server APK SHA-256:
  `b4baeddc80846a7edf1642af0059d2c99ddb7c256d0cdfa14a89697f1364f5f8`.
- Tested Phone APK SHA-256:
  `06960c0d813fb6f656f7e6d2208d27082ee5b99e767dc8e99991d5e6e10a8641`.

These tested APKs remain the release artifacts; this validation record is documentation-only and
does not trigger another APK build.

### Development baseline (2026-08-21)

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
- Locally verified Server debug APK: `Poweramp-Remote-Server-v0.10.1-debug.apk` — 420,288 bytes,
  SHA-256 `f2a8e8452f2b01d3deb9addfec147ac138eb55f05cf106b04c6892fea3e95462`.
- Locally verified Phone debug APK: `Poweramp-Remote-Phone-v0.4.2-debug.apk` — 5,201,213 bytes,
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

## Extended real-device coverage

The mandatory first-public-release matrix passed on the devices recorded above. Future compatibility
and hardening work should additionally exercise more vendors, Android versions, and adverse states:

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

Complete the physical-device matrix above for the dynamic artwork theme. Keep Server `0.10.2`, Phone
`0.5.0`, and API `v1` unchanged until a later release task explicitly assigns versions. Do not begin
the other deferred UI animation packages, multi-player foundation, pairing/transport security,
Library, Queue, or Lyrics without separate scope; their ordering remains in [`ROADMAP.md`](ROADMAP.md).
