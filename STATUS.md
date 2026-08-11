# Current project status

Current version: `0.5.0`

## Stage

Native foreground-only Android Poweramp client with an authenticated local HTTP/WebSocket API and a small embedded Web UI. There is still no separate phone application or cloud component.

## Implemented in version 0.5.0

- Added public embedded assets at `/`, `/app.css`, and `/app.js`, served by the existing `RemoteApiServer` on TCP `8765`.
- Added a phone-sized UI for artwork, title, artist, elapsed/duration seekbar, Previous, state-aware Play/Pause, and Next.
- The page performs one initial REST state request, then consumes complete state snapshots from `/api/v1/events`; there is no steady state polling.
- Position advances locally between Poweramp events. Artwork uses the existing authenticated `/api/v1/artwork` route.
- Added control action `{"action":"seek","value":37}`. The value is an absolute integer number of seconds; Poweramp receives public `Commands.SEEK` (`15`) with `pos`, followed by one delayed `POS_SYNC` request.
- Added `POST /api/v1/session` for token-to-cookie login and `DELETE /api/v1/session` for logout.
- Added random, process-local, fixed 12-hour browser sessions: 256-bit Base64URL IDs, maximum 16, `HttpOnly`, `SameSite=Strict`, path `/api/v1/`.
- Protected REST, artwork, and WebSocket routes accept either the existing Bearer token or a valid session cookie. A malformed explicit Bearer header never falls back to a cookie.
- Cookie-authenticated login/control/logout/WebSocket paths enforce exact `Origin: http://<Host>`. Bearer clients remain compatible without `Origin`.
- Session expiry, logout, and bounded-store eviction close associated WebSockets. Idle expiry uses a one-shot scheduled deadline, not Poweramp polling.
- Added CSP, clickjacking protection, no-referrer policy, bounded reconnect backoff, and a one-time auth probe after WebSocket disconnect.
- Preserved all version `0.4.0` state fields, REST/WebSocket payload format, artwork route, Bearer clients, ratings, shuffle, metadata, and transport controls.

## API contract

- Web UI: `http://<R4-IP>:8765/`.
- External API authentication: `Authorization: Bearer <token>` remains unchanged.
- Browser login: `POST /api/v1/session`, JSON `{"token":"..."}`, exact same-origin `Origin`; success returns a session cookie.
- Browser logout: `DELETE /api/v1/session` with cookie and exact same-origin `Origin`.
- State/events schema: unchanged full flat state JSON from version `0.4.0`; missing optional values are `null`.
- Seek: `POST /api/v1/control` with `{"action":"seek","value":<seconds>}`; integer range `0…2147483647`.
- Control success remains HTTP `202`: dispatched, not yet confirmed by Poweramp.
- Raw `bitRate` and `positionInList` remain unchanged from Poweramp.

The complete schema, route table, and examples are in `PROJECT.md` and `README.md`.

## Lifecycle and security limitations

- The repository still has no Android Service. The server follows the Activity lifecycle and is reachable only while the R4 app is in the foreground (`onStart` through `onStop`).
- HTTP and `ws://` are plaintext. The initial token login and session cookie must be used only on a trusted LAN; do not forward port `8765` to the internet.
- Sessions live only in the current app process. They survive `stop()/start()` on the same server object, but process death or token rotation requires entering the token again.
- Revoked browser WebSockets are closed at TCP level to guarantee non-blocking lifecycle cleanup. The page performs one `/state` auth probe after disconnect, returns to login on `401`, and otherwise reconnects with backoff.
- Lyrics remain intentionally unimplemented.

## Verification completed

Final clean verification completed on 2026-08-11:

- `56/56` JVM tests passed; `0` failures, `0` errors, `0` skipped across 12 suites.
- Loopback coverage includes public assets, session login/logout, cookie/Bearer precedence, Origin rejection, state/artwork/control, seek routing, browser WebSocket auth, expiry, logout, eviction, and event pushes.
- `lintDebug` passed with `0` errors and one non-blocking warning that Gradle `8.14.5` is available while the verified wrapper remains `8.14.3`.
- A clean `assembleDebug` succeeded; APK size is 98,560 bytes.
- APK reports `versionCode=5`, `versionName=0.5.0`, `minSdk=26`, `targetSdk=36`.
- APK Signature Scheme v2 verification succeeded with one Android debug signer.
- APK SHA-256: `7966A6C88245CD3A36700C31A1B90DD0BF56083D37E4E95AD101175DC39DB153`.
- Source ZIP was created from the documented source/configuration set and its contents were enumerated.
- A real loopback preview fixture successfully served the production server/assets on port `8765`. Automated visual interaction could not be completed because the available in-app browser runtime failed to initialize its own kernel assets (`os error 3`); no visual browser pass is claimed.

## Device checks still required

- Install the `0.5.0` debug APK on HiBy R4 and open `http://<R4-IP>:8765/` from a phone on the same LAN.
- Confirm cookie creation in the target phone browser, artwork delivery, Play/Pause state, Previous/Next, seek, and automatic WebSocket updates.
- Check session reconnect across Wi-Fi interruption, screen lock, leaving the Activity, and returning it to foreground.
- Compare raw `bitRate` against known files; do not change units before that check.
- Check first/middle/last raw `posInList`; do not add or subtract one before that check.

## Regression-sensitive functionality

Do not break:

- metadata and artwork;
- codec/file type, bit depth, sample rate, raw bitrate;
- category, raw list position and list size;
- play/pause/previous/next and absolute seek;
- rating `0…5`, Like/Dislike;
- binary shuffle and raw shuffle mode;
- automatic updates from `TRACK_CHANGED`, `STATUS_CHANGED`, `PLAYING_MODE_CHANGED`, and `TPOS_SYNC`;
- Bearer-authenticated REST/WebSocket clients;
- session-authenticated embedded Web UI.

## Next scope

No separate Android phone client was started. TLS/pairing, an always-on foreground service, and lyrics remain separate future work.
