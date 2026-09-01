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

Post-release development now contains all four requested Phone UI refinements on the existing
View-based main player: the unified dynamic artwork theme; artwork swipe and one shared track-change
transition; control morph/pulse motion plus smooth playback progress; and the compact connection
indicator with fixed metadata-chip style families. Versions intentionally remain Server `0.10.2` /
code 13 and Phone `0.5.0` / code 14; API remains `v1`. This working candidate does not change Server,
Web UI, either service, connection/playback semantics, or transport. Automated verification is
recorded below; physical-device visual/performance validation remains required before treating this
post-release series as release-ready or assigning the future Phone `0.6.0` version.

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
  palette and starts no continuous movement. The theme never drives control or metadata-chip colors;
  fixed chip families are owned separately by the fourth UI stage. Existing controls, haptics,
  descriptions, selected states, player geometry, safe insets, square artwork, and always-visible
  volume remain unchanged.

## Current post-release Phone implementation: artwork swipe and track transition

- The existing square artwork container is now a two-layer `ImageView` surface with the same 1:1
  measurement, rounded clipping, `centerCrop`, placeholder padding, content description, and compact
  weighted layout. Only this container recognizes the new gesture. Left maps to Next; right maps to
  Previous.
- A pure gesture policy owns touch slop, horizontal dominance, a 24%-width commit threshold bounded
  by touch slop, multi-touch/cancel rejection, and release confirmation. The artwork follows the
  single pointer. Vertical/diagonal or short gestures return to center, first threshold crossing
  emits one light haptic, and one gesture can produce at most one command. Disabled transport never
  enters a confirmed gesture state.
- `ArtworkPresentationStore` is now the single process presentation cache: an access-ordered LRU of
  at most three bitmap references, keyed by stable Server identity plus confirmed artwork/content
  identity. It holds no Activity, View, Context, Drawable, callback, copied/encoded bitmap, or
  credential, and never calls `recycle()` on service-owned images. Rewriting one identity updates
  its entry without increasing the bound; changing/forgetting Server isolates and clears the cache.
- The store learns adjacency only after one unambiguous local command and an authoritative matching
  snapshot: confirmed `A --Next--> B` also records `B --Previous--> A`, with Previous mirrored.
  It never derives neighbors from `positionInList`, list size, metadata, or filenames. Neutral/
  external track changes, rapid ambiguous commands, shuffle changes, reconnect, no-track, command
  error/timeout, Server change, or preview mismatch clear unreliable adjacency.
- If an exact current-identity/direction neighbor and its bitmap are cached, the spare layer is
  populated before horizontal movement and both covers follow the finger as one carousel. A short
  or cancelled gesture moves both home and hides the preview; a committed preview remains
  non-authoritative until its exact content identity is confirmed.
- Without an exact neighbor, no second/duplicate cover is invented. Pure geometry maps raw drag to
  a bounded 20%-width rubber band and committed motion to a 16%-width pending pose, so most of the
  outgoing artwork stays visible over a translucent dark, dynamic-theme-compatible rounded surface
  instead of revealing the former opaque gray card. The eventual directional transition continues
  from that actual offset without a center reset.
- Buttons and swipes call one `requestNavigation(Direction, Source)` path and one navigation/
  artwork coordinator. Each accepted action immediately forwards exactly one existing
  `controller.previous()` or `controller.next()` command. There is no optimistic metadata, track,
  or artwork state and no service/transport change.
- The coordinator uses a bounded latest-intent policy rather than a command/Animator queue. Rapid
  inputs still all reach the existing service once, while only the newest visual direction and one
  active animator survive. A new input cancels the prior animator, promotes the latest confirmed
  incoming layer from its current translation, and retargets it deterministically. If intermediate
  snapshots are coalesced, the final delivered snapshot/artwork remains authoritative. A stale
  2.2-second generation-bound presentation timeout only recenters an unconfirmed pending pose; it
  does not poll, cancel a command, or override a newer input.
- Next moves the outgoing cover left and the confirmed incoming cover from the right; Previous is
  mirrored. A track change with no pending local action uses a neutral cross-fade through the same
  coordinator. Command failure, disconnect, Activity stop, or rebind recenters the latest confirmed
  content and suppresses a stale requested direction.
- The controller's normal `onArtworkChanged(null)` now means loading and never immediately replaces
  the outgoing cover. The current `trackIdentity()` plus Server-aware `artworkKey()` and an Activity-
  local generation reject old deliveries. Genuine no-artwork/decode/network failure changes to the
  ordinary placeholder only after the bounded 1.5-second generation-bound grace period.
- `onStateChanged()` checks the same bounded store as soon as a new identity is authoritative. An
  exact cached bitmap immediately continues the one transition path; the later identical service
  delivery is an in-place layer update, not a second animation or position reset. A cached mismatch
  is rejected before correct artwork is awaited. Binder replay/configuration recreation can still
  paint current content immediately without a false track transition.
- The live surface holds no more than outgoing and incoming layers, uses one retargetable animator,
  performs only View translation/alpha per frame, and never processes, copies, or recycles a bitmap.
- Confirmed artwork continues through the existing `ArtworkThemeRequestGate`, Server/artwork-keyed
  `ArtworkPaletteRepository`, and background transition. Gesture/motion code performs no palette
  analysis or artwork request. Disabled animator scale leaves commands active, skips pending and
  content tweening, and applies the final confirmed artwork immediately.

## Current post-release Phone implementation: control motion and smooth progress

- Play/Pause now uses one custom tint-aware Drawable whose single filled play endpoint and compatible
  intermediate geometry morph cleanly into pause bars. Shuffle uses the same bounded pattern to
  morph two parallel non-crossing OFF arrows into cleanly joined crossed ON arrows. Previous/Next
  use short directional glyph nudges. Button bounds, padding, haptics, enabled alpha, and touch
  targets do not move; rounded pressed-state backgrounds replace the former foreground ripple.
- Each binary control has a pure confirmed-state policy. Initial state and binder/configuration replay
  apply the endpoint immediately, duplicate snapshots return no motion, and a rapid confirmed reverse
  cancels and retargets one active animator from its current progress. Content descriptions, Shuffle
  selected tint, and Android 11+ state descriptions change synchronously with the confirmed snapshot.
- Like and Dislike keep their existing vectors and selected tint inside matching Drawable wrappers.
  Each scales only its glyph to 1.14× and softly returns over 520 ms for a visible confirmed change
  to rating `5` or `1`. Initial/rebound state, duplicate active rating, and removal do not pulse; a
  contrary rating cancels and resets the same animator.
- An accepted Play/Pause tap retargets the glyph immediately while sending the existing command. A
  matching newer snapshot confirms it without replay; mismatch, command failure, bounded timeout,
  stop, or rebind restores the confirmed state. `MainActivity.onStop()` settles all six motion
  Drawables and removes playback callbacks; motion buttons also settle on detach. Animator scale `0`
  applies endpoints immediately and skips nudges/pulses.
- Playback progress now gives the existing platform `SeekBar` millisecond presentation units while
  commands remain one rounded integer second. The thumb is sampled from the unchanged service-owned
  `PlaybackUiSnapshot.positionMillisecondsAt(monotonicNow)`: callback delivery time never replaces
  the WebSocket receipt anchor and `RemoteSessionPlayer` remains unchanged on the same snapshot.
- A pure `PlaybackProgressCoordinator` stores only presentation state around that authoritative
  object. A same-track playing discrepancy from 25 through 1500 ms becomes a current-to-authoritative
  offset that reaches zero over a bounded 280 ms smoothstep. Smaller differences need no visible
  correction; larger discontinuities, track change, pause/resume, reconnect/rebind, disabled motion,
  and seek timeout/failure apply the authoritative position immediately.
- Manual drag stops local scheduling and owns the thumb/elapsed label. Release emits one existing
  integer-second seek and one haptic, then a two-second presentation-only pending seek advances from
  the requested point while rejecting old snapshots. Confirmation returns to the remote anchor;
  timeout, command failure, disconnect, or track change discards the pending override and returns to
  the last confirmed position.
- One retained `Choreographer.FrameCallback` runs only while Main is visible, connected, confirmed
  playing, has a positive duration, and is not dragging. Every frame recomputes from monotonic time,
  creates no Drawable/Animator, and changes elapsed text only on a whole-second boundary. `onStop()`
  removes both frame and Handler callbacks. With animator scale disabled, precise position continues
  at a conservative 250 ms local cadence without any extra REST/WebSocket traffic.
- The non-scrolling layout, square artwork, cached swipe transition, dynamic palette path, volume,
  metadata formatting, control colors, services, transport, pairing/reconnect, MediaSession/Wear,
  notification, Server/Web UI, and API `v1` remain unchanged.

## Current post-release Phone implementation: connection indicator and metadata chip styles

- The top-right action still opens the existing `PlayerDevicesActivity`, but now renders a compact
  one-line pill without a square ripple. Its existing 40 dp touch area contains a 36 dp rounded
  normal/pressed surface, a fixed-color status dot, localized text, and a localized description that
  combines current state with the Player devices action. The top-bar height and left menu are
  unchanged; auto-sized one-line text and a bounded width protect compact/font-scaled layouts.
- One exhaustive pure `ConnectionIndicatorPolicy` maps every current `RemoteClientController.Status`:
  `CONNECTED` → LAN, `CONNECTED_DIRECT` → Wi-Fi Direct, search/pair/verify/connect/direct-search/
  direct-connect/retry → Connecting, and permission/location/Wi-Fi/action/auth/unsupported/error →
  Disconnected. An Activity-local tracker suppresses duplicate presentation updates.
- Binding reads the already available `PlayerDeviceSnapshot.runtimeStatus` before listener replay, so
  the pill shows current state immediately. Later changes arrive only through the existing controller
  callback; there is no polling, service model, endpoint display, or connection-path change.
- A pure `MetadataChipStylePolicy` consumes only raw `fileTypeName`/`codec`, bit depth, sample rate,
  and bitrate. Codec/container matching is case-insensitive with `Locale.ROOT`, explicitly covering
  FLAC, MP3, AAC/M4A, ALAC, WAV/PCM, AIFF, APE, OGG/Vorbis, Opus, WMA, and fallback. Bit depth has
  related 16/24/32/fallback variants; standard 44.1–192 kHz rates, higher rates, and nonstandard rates
  use one related sample-rate family; bitrate deliberately retains one muted amber/neutral style.
- Formatted chip values, API parsing, tolerant bitrate behavior, chip size/spacing/scrolling, and
  missing-value visibility are unchanged. An Activity-owned enum-bounded factory lazily caches at
  most one dark translucent bordered `GradientDrawable` per style, with no static Context or artwork
  input. Repeated metadata/position snapshots reuse the current style and allocate no Drawable or
  animation.
- Dynamic artwork, artwork swipe/cache/transitions, all control animations, smooth seek, volume,
  pairing/reconnect, LAN/NSD and Wi-Fi Direct, `PhoneConnectionService`, MediaSession/Wear,
  notification, Server/Web UI, and API `v1` remain unchanged.

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
- Seek and volume use dedicated player-style tracks/thumbs. Playback seek now has millisecond visual
  progress while retaining one integer-second remote command; volume retains exact integer control.
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

### Connection indicator/metadata-style automation (2026-09-01)

- The required final clean debug pipeline completed through pinned Gradle Wrapper `8.14.3` with
  Temurin JDK 17, Java 17 source/target, Android compile/target 36, and Build Tools 35.0.0:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache --no-daemon --console=plain`. All
  `100/100` actionable tasks executed from clean outputs.
- Server passed its unchanged `78/78` JVM tests across 18 suites. Phone passed `134/134` across 28
  suites, for 212 total executions with zero failures, errors, or skips. The 14 added pure executions
  cover every controller status, LAN/direct/connecting/disconnected grouping, duplicate presentation
  suppression, absence of diagnostic data, deterministic/root-normalized codec families, bit-depth
  and sample-rate variants/fallbacks, bitrate neutrality, invalid optional metadata, and complete
  isolation from artwork palette state.
- Phone lint reports no issues. Server lint has zero errors and only its two unchanged informational
  update warnings for pinned Gradle `8.14.3` and JVM-test-only `org.json`; no lint rule was disabled
  or weakened. The obsolete `ic_devices` vector was removed after the new pill made it unreachable.
- Actual debug APK badging confirms unchanged Server package `dev.r4remote.poweramp`, code 13/name
  0.10.2, and Phone package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26,
  target/compile API 36, Phone `localeConfig`, and the unchanged API v1 constants/routes.
- Merged manifests retain exactly one non-exported Server
  `RemotePlaybackService(connectedDevice)` and one exported Media3 Phone
  `PhoneConnectionService(connectedDevice|mediaPlayback)`, both with `stopWithTask=false`. No service,
  Activity, permission, provider, receiver, application ID, dependency, connection/runtime,
  MediaSession, notification, Server/Web UI, or API change was introduced.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  Android debug signer. This stage does not create a release build, tag, release, commit, push, or
  published artifact, and it does not access or expose release-signing configuration.
- `git diff --check` and the final changed-file/generated-artifact/public-repository audit pass. No
  tracked APK/AAB/build output, signing material, credential, QR payload, IP/SSID, local path, or new
  dependency was added. Physical-device state, localization, accessibility, contrast, and compact-
  layout validation remains outstanding in the matrix below.

### Post-release control-motion/smooth-progress automation (2026-08-31)

- The required clean debug pipeline completed through pinned Gradle Wrapper `8.14.3` with Temurin
  JDK 17, Java 17 source/target, Android compile/target 36, and Build Tools 35.0.0:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache --no-daemon --console=plain`. All
  `100/100` actionable tasks executed from clean outputs.
- Server passed its unchanged `78/78` JVM tests across 18 suites. Phone passed `120/120` across 26
  suites, with zero failures, errors, or skips. The 16 new pure executions cover initial/immediate,
  confirmed bidirectional and rapid-retarget control motion, duplicate/disabled/replay behavior,
  one-shot Like pulse, Shuffle endpoints, original monotonic-anchor extrapolation, pause/clamp,
  bounded reconciliation endpoints, large/track discontinuities, correction reset, callback-delay
  handling, drag/pending-seek priority, seek confirmation/failure/timeout, lifecycle/reduced-motion
  scheduling, and whole-second label boundaries.
- Phone lint reports no issues. Server lint has zero errors and only its two unchanged informational
  update warnings for pinned Gradle `8.14.3` and JVM-test-only `org.json`; no lint rule was disabled
  or weakened.
- Actual debug APK badging confirms unchanged Server package `dev.r4remote.poweramp`, code 13/name
  0.10.2, and Phone package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26,
  target/compile API 36, Phone `localeConfig`, and the unchanged API v1 constants/routes.
- Merged manifests retain exactly one non-exported Server
  `RemotePlaybackService(connectedDevice)` and one exported Media3 Phone
  `PhoneConnectionService(connectedDevice|mediaPlayback)`, both with `stopWithTask=false`. No service,
  Activity, permission, provider, receiver, application ID, dependency, connection/runtime,
  MediaSession, notification, Server/Web UI, or API change was introduced.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  Android debug signer. This stage does not create a release build, tag, release, commit, push, or
  published artifact, and it does not access or expose release-signing configuration.
- `git diff --check` passes. The final changed-file/generated-artifact/secret audit finds no tracked
  APK/AAB/build output, signing material, credential, QR payload, IP/SSID, local path, or new
  dependency. Physical-device motion, frame-pacing, lifecycle-load, and layout validation remains
  outstanding in the matrix below.

### Artwork cached-neighbor visual correction automation (2026-08-30)

- The required clean debug pipeline completed through pinned Gradle Wrapper `8.14.3` with Temurin
  JDK `21.0.12.1`, Java 17 source/target, Android compile/target 36, and Build Tools 35.0.0:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache`. All `100/100` actionable tasks ran from
  clean outputs.
- Server passed its unchanged `78/78` JVM tests across 18 suites. Phone passed `104/104` across 24
  suites, with zero failures, errors, or skips. The 34 focused swipe/presentation executions cover
  direction/threshold/cancellation/haptic/one-command rules, a common button/gesture request path,
  the hard three-entry LRU, duplicate replacement, Server isolation, confirmed mirrored adjacency,
  exact neighbor lookup, neutral changes, predicted-identity mismatch, immediate cached apply,
  update-only late delivery, rapid ambiguous intent, abort/disconnect policy, bounded no-cache
  rubber-band geometry, carousel geometry, continuous transition endpoints, stale generations,
  grace fallback, binder replay, and disabled animations.
- Phone lint reports `No issues found` (zero errors and warnings). Server lint has zero errors and
  only its two unchanged dependency-update notices for pinned Gradle `8.14.3` and the JVM-test-only
  `org.json` artifact. No lint rule was disabled or weakened.
- Actual debug APK badging confirms unchanged Server package `dev.r4remote.poweramp`, code 13/name
  0.10.2, and Phone package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0; both retain min API 26,
  target/compile API 36, and local API v1 constants/routes.
- Merged manifests retain exactly one non-exported Server
  `RemotePlaybackService(connectedDevice)` and one exported Media3 Phone
  `PhoneConnectionService(connectedDevice|mediaPlayback)`, both with `stopWithTask=false`. No
  service, Activity, permission, provider, receiver, dependency, application ID, Server/API/Web UI,
  transport, pairing, notification, or MediaSession change was introduced.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  debug signer. This task deliberately does not create, sign, tag, publish, push, or commit a
  release artifact; external release-signing properties were neither needed nor exposed.
- `git diff --check` passes. The final changed-file/generated-artifact/secret audit found no tracked
  APK/AAB/build output, signing material, credential, QR payload, IP/SSID, local path, or new
  dependency. Physical-device visual/frame-pacing validation remains outstanding as listed below.

### Post-release artwork-navigation automation (2026-08-30)

- One clean no-build-cache debug pipeline completed through the pinned Gradle Wrapper `8.14.3`
  with Temurin JDK 21, Java 17 source/target, Android compile/target 36, and Build Tools 35.0.0:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug`. All `100/100` actionable tasks executed from clean
  outputs.
- Server passed its unchanged `78/78` JVM tests across 18 suites. Phone passed `89/89` across 24
  suites, with zero failures, errors, or skips. The 19 added executions cover swipe direction and
  threshold, short/vertical/diagonal/cancel/multi-touch rejection, one haptic/command, unavailable
  controls, the common button/swipe command path, one-entry rapid intent, stale timeout and artwork
  generations, metadata-before-artwork, intermediate `null`, grace placeholder, binder replay,
  error/disconnect recovery, reduced motion, one-entry presentation retention, and exact slide/
  retarget geometry.
- Phone lint reports `No issues found` (zero errors and warnings). Server lint has zero errors and
  only its two existing dependency-update notices for pinned Gradle `8.14.3` and the JVM-test-only
  `org.json` artifact. No lint rule was disabled or weakened.
- Actual debug APK badging confirms unchanged Server package `dev.r4remote.poweramp`, code 13/name
  0.10.2, and Phone package `dev.r4remote.poweramp.phone`, code 14/name 0.5.0. Both retain min API 26
  and target/compile API 36.
- Merged manifests retain exactly one Server `RemotePlaybackService(connectedDevice)` and one Phone
  `PhoneConnectionService(connectedDevice|mediaPlayback)`, both with `stopWithTask=false`. Phone
  keeps its `localeConfig` and the same Main, Player devices, Settings, About, and scanner
  Activities. No service, permission, provider, receiver, application ID, dependency, or API route
  was added.
- Both debug APKs pass 16 KiB-aware ZIP alignment and APK Signature Scheme v2 verification with one
  debug signer. This stage does not build, sign, publish, tag, push, or commit a release artifact.
- `git diff --check` and the final changed-file/generated-artifact/public-repository audit pass.
  Server source/Web UI, both services, MediaSession, connection/transport/pairing code, API v1,
  Gradle dependency declarations, version metadata, signing configuration, and release history are
  unchanged.

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

### Connection indicator/metadata styles real-device checks still required

Do not mark this fourth unversioned Phone UI stage complete until the exact candidate passes on a
physical Phone device:

- Verify the pill shows LAN on ordinary NSD/LAN, Wi-Fi Direct on direct fallback, Connecting during
  discovery/connection/retry, and Disconnected for ordinary loss plus every reachable permission,
  Location Mode, Wi-Fi, user-action, auth, unsupported, and error state. No endpoint, API version, or
  Server ID may appear on Main.
- Tap every pill state and confirm it always opens the existing Player devices screen with its full
  diagnostics/actions. The pill must press as a rounded surface without a square ripple; haptic,
  Back, pairing, re-pair, forget, permission recovery, LAN preference, and reconnect must remain
  unchanged.
- Repeat the status matrix in English and Russian, including an Activity stop/start, service rebind,
  rotation/configuration recreation, disconnect, and reconnect. The current status and localized
  state-plus-action description must appear immediately without polling or stale text.
- Check compact portrait, landscape, cutout/navigation-inset layouts, and increased font scale. The
  one-line pill may shrink or ellipsize but must stay inside safe insets, retain its dot and action,
  keep the 40 dp top bar, and never displace square artwork or the always-visible volume control.
- Exercise FLAC, MP3, AAC/M4A, ALAC, WAV/PCM, AIFF, APE, OGG/Vorbis, Opus, WMA, and an unknown codec;
  16/24/32/other bit depth; 44.1/48/88.2/96/176.4/192/high/nonstandard sample rates; and bitrate.
  Confirm each family is muted, internally related, bordered, readable, and stable for identical raw
  metadata in both app languages.
- Change among strongly different artwork while keeping identical metadata, then exercise normal
  playback-position snapshots. Chip colors must not follow the artwork or animate/reallocate on
  position-only updates. Missing optional values remain hidden and existing formatter output,
  horizontal scrolling, chip spacing, artwork/control motion, seek, and volume remain intact.

### Control motion/smooth-progress real-device checks still required

Do not mark this third unversioned Phone UI stage release-ready until the exact candidate passes on
a physical Phone device:

- Exercise Play/Pause during ordinary playback, then change confirmed state rapidly in both
  directions. The inner glyph must reverse smoothly from its current shape while the primary button,
  ripple, touch target, content description, and command behavior stay fixed. Repeat an external
  change from Poweramp, notification, MediaSession/lock screen, and Wear.
- Add and remove Like, repeat duplicate rating `5`, move rapidly among ratings, and change rating
  externally. Only a newly confirmed non-Like → `5` transition while Main is visible may pulse once;
  selected tint and exact Poweramp rating semantics must remain correct.
- Toggle Shuffle OFF/ON normally and rapidly, then change it externally. Confirm parallel OFF arrows,
  recognizable crossed ON arrows, one retargeted morph, immediate selected/content/state description,
  and unchanged binary command behavior.
- Observe seekbar motion through long playback and around whole-second label boundaries. Confirm a
  smooth thumb, no extra network traffic, no cumulative drift, no visible frame drops, and agreement
  with notification/MediaSession position.
- Repeat pause/resume and manual seek, including slow confirmation, stale intermediate snapshots,
  command failure, and timeout. The thumb must not fight a finger; release must send one command and
  one haptic; confirmation must continue from the remote anchor and failure must restore it.
- Change tracks with buttons, artwork swipe, rapid mixed Next/Previous, and an external automatic
  transition while progress is moving. Duration/position correction must reset to the latest
  confirmed track without disturbing the artwork theme/cache/transition path.
- Background/resume Main, rotate/configuration-recreate it, and disconnect/reconnect during playing,
  paused, morphing, pulsing, correcting, and pending-seek states. Rebind must immediately show current
  endpoints and current extrapolated service position without a false celebration or stale callback
  re-anchor.
- Set animator duration scale to `0` before launch and while Main is visible. Play/Pause and Shuffle
  must snap to confirmed endpoints, Like must not pulse, and playback position must remain accurate at
  the conservative cadence. Restore scale and verify later confirmed changes animate normally.
- Repeat on compact portrait and landscape/cutout/navigation-inset layouts. All controls, square
  artwork, metadata, seek precision, always-visible volume, haptics, selected states, touch targets,
  and accessibility descriptions must remain intact.
- Profile CPU/frame scheduling with Main visible and hidden. No progress frame callback or decorative
  animator may run after `onStop()`/detach, and no new artwork request, network polling, service,
  MediaSession path, or growing animator chain may appear.

### Artwork swipe/track-transition real-device checks still required

Do not mark this second unversioned Phone UI stage release-ready until the exact candidate passes on
a physical Phone device:

- Navigate for the first time to an artwork that has never been cached. During drag and the load
  gap, confirm the current cover uses only a restrained elastic/pending offset, most of it stays
  visible, and the revealed rounded area shows the dark dynamic background rather than a gray card
  or a duplicate cover.
- Return to the just-viewed track, then move forward again across that now-known pair. In both
  directions the cached neighbor must appear beside the current cover under the finger, move as one
  carousel, become current only after the matching snapshot, and avoid a second transition when the
  service later delivers the same bitmap.
- On current artwork, make a short horizontal drag in both directions and release below threshold;
  test both cached and uncached directions, and confirm all visible layers return smoothly, preview
  is hidden, no command is produced, and metadata, square crop/rounding, seek, and volume remain
  undisturbed. Repeat vertical, clearly diagonal, cancelled, and two-finger gestures; none may
  navigate.
- Cross the threshold left and right. Confirm one light haptic per gesture, exactly one Next for
  left and Previous for right, outgoing/incoming direction, and no additional haptic when crossing
  the threshold repeatedly before release. Repeat with controls unavailable/disconnected.
- Exercise Previous/Next buttons and alternate buttons with swipes. Both sources must use the same
  transition, keep their existing button ripple/haptic/accessibility behavior, and send one command
  per accepted action.
- Perform rapid Next/Previous sequences, including mixed directions while a prior cover is still
  moving and while Server snapshots are coalesced. Confirm one active visual transition, no growing
  queue, no jump back to an old/cached cover, no stale preview after an identity mismatch, and final
  rest on the newest confirmed remote track. Repeat after enabling Shuffle; no old adjacency may be
  treated as a known queue neighbor.
- Throttle or disrupt artwork loading. The expected intermediate `null` must retain the outgoing
  cover with no placeholder flash; a current valid bitmap must enter only for its matching track,
  while genuine missing/failed artwork reaches the normal placeholder after the grace period.
- Change tracks externally from Poweramp, Phone notification, MediaSession/lock screen, and Wear.
  Confirm the same coordinator uses a neutral transition and never guesses Previous/Next.
- Background/reopen Main, open and return from another Activity, disconnect/reconnect, and rotate
  during idle, drag, pending command, artwork load, and content transition. Binder replay must show
  current content immediately without a false track animation; stop/error paths must recenter the
  latest confirmed content.
- Repeat on compact portrait and landscape/cutout/gesture-navigation layouts. Artwork remains square
  and clipped, all metadata/controls remain reachable, volume stays visible, and no gesture leaks
  outside the artwork container.
- Disable system animator duration scale, then repeat buttons, both swipe directions, external track
  change, and rebind. Commands must still work, direct dragging may follow the finger, and confirmed
  content must snap to final state with no continuing animator.
- Profile normal playback, a single track change, rapid mixed input, slow artwork, Activity return,
  and rotation for frame pacing and memory. Confirm no bitmap decoding/processing per frame, no
  more than three process-cache bitmap entries, no more than two live artwork layers, no animator
  accumulation, and no extra network/artwork/palette request.

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

Complete the physical-device matrices above for all four Phone UI packages: dynamic artwork theme,
unified artwork navigation/track transition, control motion/smooth progress, and the connection
indicator/fixed metadata styles. Keep Server `0.10.2`, Phone `0.5.0`, and API `v1` unchanged until a
later task performs the one coordinated Phone `0.6.0` version increase. Do not begin Player devices
redesign, artwork-dependent control/chip colors, multi-player foundation, pairing/transport
security, Library, Queue, or Lyrics without separate scope; their ordering remains in
[`ROADMAP.md`](ROADMAP.md).
