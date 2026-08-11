# Current project status

Current version: `0.6.0`

## Stage

Native Android Poweramp client with one started-and-bound foreground service, an authenticated
local HTTP/WebSocket API, and a compact embedded Web UI. There is no separate phone application,
WebView, or cloud component.

## Implemented in version 0.6.0

- Added `RemotePlaybackService`, the single owner of `PowerampClient`, `PlaybackStateStore`,
  `RemoteArtworkCache`, `RemoteApiServer`, browser sessions, and WebSocket connections.
- Declared and started it as a `connectedDevice` foreground service with the matching Android
  permissions and an ongoing low-importance notification. Android 13+ notification permission is
  requested by the Activity.
- The service returns `START_STICKY`; repeated starts are idempotent and also retry a failed server
  bind or Poweramp availability check. A tested generation guard drops commands queued across a
  stop/restart boundary.
- `MainActivity` starts the service, binds only while visible, and unbinds without stopping it.
  Backgrounding the Activity, removing it from recent apps, or locking the screen no longer closes
  REST/WebSocket sockets or unregisters Poweramp receivers.
- The notification Stop action immediately stops the network runtime and Poweramp receivers.
  `onDestroy()` closes server sockets, WebSockets, sessions, executors, artwork state, and receivers.
- Enabled TCP keepalive on accepted sockets. No partial wakelock, Wi-Fi lock, wake permission, or
  battery-optimization exemption was added; target-device evidence is required before adding one.
- Expanded the mobile-first Web UI with album, codec/file type, bit depth, sample rate, bitrate,
  source category, list position/size, Like, Dislike, rating reset, and Shuffle OFF/ON while
  retaining artwork, transport controls, seek, and local elapsed-position rendering.
- Web UI state remains one initial REST snapshot plus complete event-driven WebSocket snapshots.
  Controls use the existing REST command endpoint and do not poll or create a WebSocket command
  channel.

## Preserved API and security contract

- Port, paths, JSON state schema, command bodies, and HTTP status semantics are unchanged.
- External `Authorization: Bearer <token>` clients remain compatible.
- Same-origin browser session login, `HttpOnly; SameSite=Strict` cookie authentication, Origin
  checks, bounded sessions, logout/expiry/eviction behavior, artwork auth, and CSP remain intact.
- Raw `bitRate` and raw `positionInList` remain unchanged in REST/WebSocket JSON.
- HTTP and `ws://` remain plaintext and must be used only on a trusted LAN; do not forward port
  `8765` to the internet.
- Sessions remain process-local. Activity recreation/backgrounding keeps them; process death,
  explicit service stop followed by destruction, or token rotation does not.

## Verification completed

Final clean verification completed on 2026-08-12:

- `clean testDebugUnitTest lintDebug assembleDebug` succeeded with all `50` Gradle tasks executed.
- `59/59` JVM tests passed; `0` failures, `0` errors, `0` skipped across `13` suites.
- Added lifecycle coverage for idempotent start, stale-command invalidation, restart, and final close.
- Expanded Web UI assertions cover every required metadata field and rating/shuffle command while
  retaining loopback REST/WebSocket/session/auth regression coverage.
- Embedded production JavaScript passed a separate Node syntax check; the production assets were
  also served successfully by a loopback preview on `127.0.0.1`.
- Automated visual browser interaction could not run because the available browser runtime could
  not access its local profile (`EPERM`); no visual viewport pass is claimed.
- `lintDebug` passed with `0` errors and one non-blocking warning that Gradle `8.14.5` is available
  while the verified wrapper remains `8.14.3`.
- Fresh debug APK size: `107,770` bytes.
- APK reports `versionCode=6`, `versionName=0.6.0`, `minSdk=26`, `targetSdk=36`.
- Merged/APK manifests contain `RemotePlaybackService`, `foregroundServiceType="connectedDevice"`,
  `stopWithTask="false"`, and the required foreground/notification/network permissions.
- APK Signature Scheme v2 verification succeeded with one Android debug signer.
- APK SHA-256: `797876314D7EDAFBD727B917C12A39461CB1D73256A466E327F8F5B104E4C166`.
- Source search confirms no `WAKE_LOCK`, `WifiLock`, or `CHANGE_WIFI_STATE` declaration/use.

APK: `outputs/R4-Poweramp-Remote-v0.6.0-debug.apk` (delivery copy) and
`app/build/outputs/apk/debug/app-debug.apk` (standard Gradle output).

## HiBy R4 device checks still required

- Install the `0.6.0` debug APK, grant notifications, and confirm the ongoing service notification
  appears with a working Open and Stop path.
- Open the Web UI from an ordinary phone and confirm the player fits without vertical scrolling,
  artwork/metadata formatting, all transport/seek/rating/shuffle controls, and session login.
- Keep `websocat` or the browser connected, then background/remove the Activity and lock/turn off
  the R4 screen. Verify REST, the existing WebSocket, and direct Poweramp events after 5, 30, and
  60 minutes, both while music is playing and while paused.
- Confirm the notification Stop action closes the listening port and WebSockets, then reopening the
  app starts a clean server and reconnects to current Poweramp state.
- If the R4 reproducibly suspends network/CPU with the screen off, capture duration, playback state,
  battery settings, and logs before considering a narrowly scoped lock. Do not add both locks by
  default.
- Compare raw `bitRate` against known files and check first/middle/last raw `posInList`; do not
  change units or offsets before that evidence exists.

## Regression-sensitive functionality

Do not break:

- background foreground-service ownership and clean repeated start/stop;
- metadata/artwork and audio/source details;
- play/pause/previous/next, absolute seek, rating `0…5`, Like/Dislike/reset, and shuffle;
- automatic updates from `TRACK_CHANGED`, `STATUS_CHANGED`, `PLAYING_MODE_CHANGED`, and `TPOS_SYNC`;
- Bearer-authenticated REST/WebSocket clients;
- session-authenticated embedded Web UI and its exact-origin protections.

## Next scope

The next required work is the HiBy R4 screen-off/device matrix above. Add a wakelock or Wi-Fi lock
only if that matrix demonstrates a specific failure that the foreground service alone does not
solve. TLS/pairing remains future work. A separate phone client and lyrics remain out of scope.
