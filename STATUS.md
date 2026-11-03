# Current project status

## Release state

- Published baseline: Server `0.11.0` / code 14 and Phone `0.7.0` / code 16.
- Next candidate: Phone `0.8.0` / code 17 with the unchanged public Server `0.11.0` APK.
- Local API: backward-compatible `v1`.
- Published baseline application-source revision: `6bb22a8`
  (`6bb22a87c77a7563e4855b460c1507b00def1ecc`).
- Phone `0.8.0` APK-source revision: `9616976`
  (`9616976a16a8cdf1daf91f3515807b9c7fc6e8bc`).

The release is already published. Its signed in-place matrix passed over Server `0.10.2` / Phone
`0.6.0`, preserving Server identity/token and Phone pairing, credentials, language, and application
data. The published tag and APKs identify the application-source revision above; subsequent
connection-policy work below is not part of those binaries.

## Connection policy and recovery — completed implementation

Implementation was committed as `ee6795a` (`feat(phone): add transport preferences and resilient
reconnect`) on `codex/connection-policy`, based on `main` at `d4f8733`. The maintainer confirmed the
complete manual matrix on 2026-11-03. Release preparation advances only Phone to `0.8.0` / code 17;
Server source and build configuration are identical to the published release.

Confirmed source-level causes and fixes:

- Hard-coded LAN-first callbacks destroyed Direct before authenticating LAN. Three saved modes
  (`LAN_ONLY`, default `LAN_PREFERRED`, `DIRECT_PREFERRED`) now govern automatic selection, manual
  selection, pairing, and recovery. A bounded replacement probe keeps the working socket until its
  replacement supplies a valid snapshot. Direct preference keeps a healthy LAN fallback to avoid
  oscillation; a new explicit policy/manual action can retry the preferred path.
- Repeated recovery callbacks reset fallback timers; same-address WebSocket failures retried
  forever while Wi-Fi remained up. Earlier recovery deadlines now win. Confirmed LAN loss skips
  the initial grace; two failures or 15 seconds after first failure consider alternatives and
  quarantine the failed endpoint for 60 seconds. NSD failure/loss does not replace a healthy status.
- NSD could report a P2P-interface address as LAN. Public NSD Network (API 33+) or a verified
  on-link infrastructure route (26–32) identifies LAN; LAN HTTP/WebSocket traffic binds to that
  Network. Group existence alone never confirms a connection.
- HTTP Upgrade without a snapshot had no overall deadline, and a missing P2P action callback
  bypassed negotiation timeout. First-snapshot deadline is now 25 seconds total (existing 5-second
  TCP + 10-second upgrade + 10-second snapshot allowance); P2P's existing 30-second timer starts
  before its action callback. The 20-second idle Ping plus 20-second reply timeout is unchanged.
- Pending/confirmed socket tokens and P2P operation/channel generations reject stale callbacks.
  Group release precedes a replacement negotiation. One dropped-group recovery is allowed, but a
  rejected/timed-out negotiation requires explicit retry. API-only Direct failure can reuse its
  verified group after Server restart. Queued commands check generation before dispatch, and no
  Queue-add or other mutation is replayed after ambiguous network failure.
- Player devices has two transport actions, confirmation for LAN under Direct preference, a
  temporary manual priority, a 60-second attempt bound, safe failure reasons, and current-transport/
  concurrent-operation gating. Cancelled confirmation leaves the runtime untouched. Network
  failures retain pairing; authentication rejection requests Re-pair without deleting stored data.
- Settings has persisted transport switches and Developer mode (off by default). One typed safe
  event reaches both Logcat and the optional process-memory buffer (100 one-line entries). Off
  clears/stops collection; the log dialog snapshots on Refresh, preserves reading offset, and
  includes Clear/Close. No disk logs, secrets, raw exceptions, polling, or new runtime are added.

Earlier implementation verification (2026-10-25, before the release version bump):

```text
./gradlew clean :app:testDebugUnitTest :phone:testDebugUnitTest \
  :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug
```

- Server **147/147**, Phone **221/221**, total **368/368**, zero failures/errors/skips. This includes
  new policy/manual-priority/fallback/attempt-token/deadline/buffer tests and real loopback tests
  for an upgraded socket without a snapshot and an ambiguous Queue-add disconnect without retry.
- Both debug lint tasks pass: Server has 2 warnings and Phone 27 warnings, zero errors. Build output
  also notes the API-26-compatible initial `getAllNetworks()` seed is deprecated, existing Gradle
  deprecations, and the installed command-line tools' SDK XML-version advisory.
- Both clean debug APKs build. Follow-up checks were limited to Phone test/lint/assemble after
  review corrections for old-LAN loss during a new probe, queued pairing cancellation, P2P channel
  release on shutdown, and interrupted negotiation without repeated approval. Safe diagnostic
  reason/duration fields were also rechecked. All passed; no further Server run was needed.
- APK manifests retain min API 26, target/compile 36, Server `0.11.0` / code 14, Phone `0.7.0` /
  code 16, and the existing single runtime service in each application. Both APKs verify with
  Signature Scheme v2 and Android Debug certificate SHA-256
  `4d2c7c0d0f8f2b81495d62884a96f361650573d8e99635a6f8cc754e36ec6265`;
  both pass `zipalign -c -P 16 4`.
- `git diff --check` passes. Server source, manifests, API `v1`, version declarations, release APKs,
  release tags, and published assets are unchanged.

Physical timing measurements are not yet available; the times above are nominal source/protocol
bounds and Android suspension can delay callback delivery. The loopback test's injected 500 ms
first-snapshot deadline completed in about 504 ms; this is JVM evidence, not a screen-off/OEM timing
claim. The maintainer subsequently confirmed the complete functional device matrix below;
no quantitative physical timing results were supplied.

### Direct-preferred reconnect regression (2026-10-27)

- The reported Server stop/restart regression is explained by two source paths:
  `handleSocketFailure()` called `unavailable(DIRECT)`, immediately allowing LAN even with a usable
  verified group; the first LAN snapshot then stopped Direct and correctly—but prematurely—held
  that fallback. Earlier fallback permission was not cleared by confirmation, and timeout-set
  `directSuppressed` could survive indefinitely into later automatic recovery.
- A confirmed-path loss now starts one fresh 60-second `DIRECT_PREFERRED` recovery window. A
  repeated socket/group callback cannot reset it or release LAN early. Confirmation ends the
  cycle and clears its fallback state. Manual priority, `LAN_ONLY`, and `LAN_PREFERRED` remain
  covered by targeted regressions.
- With a usable verified group, API recovery keeps the existing 1/2/4/8/15-second backoff during
  that window instead of applying the generic two-failure/15-second endpoint quarantine. It does
  not renegotiate Android. Group loss uses the existing bounded P2P recovery instead. Expiry opens
  LAN fallback; a retained group is released only after LAN supplies a valid snapshot.
- If still disconnected after expiry, a later matching endpoint discovery or the existing
  60-second cooldown retry starts a new bounded cycle, clearing transient suppression and old
  rejection of the reusable Direct endpoint. No new cycle starts while connecting or healthy.
  Terminal permission/negotiation/manual-switch failure is tracked separately and still requires
  user retry, including a failure delivered while transient fallback suppression is active.
  Healthy confirmed LAN fallback is not repeatedly displaced.
- Safe diagnostic reasons now distinguish `RECOVERY_DIRECT_PRIORITY`, `RECOVERY_REUSE_GROUP`,
  `RECOVERY_DISCOVER_DIRECT`, `RECOVERY_LAN_FALLBACK`, `RECOVERY_SELECT_DIRECT`, and
  `RECOVERY_SELECT_LAN`. No endpoint, credential, payload, or raw exception is logged.
- Targeted verification only (the earlier full run above was not repeated): `ConnectionPolicyTest`,
  `DirectPreferredRecoveryTest`, `WifiDirectRecoveryPolicyTest`, and `TransportRecoveryPolicyTest`;
  **24/24** tests, zero failures/errors/skips; `:phone:assembleDebug` and `git diff --check` pass.
  No clean, lint, Server tasks, full suites, release tasks, API or version changes were made for
  this correction. The existing connection-policy work was preserved and is now committed.
- Device verification is **maintainer-confirmed on 2026-11-03**, including the complete manual
  matrix and the reported Server stop/restart correction. Final signed-candidate checks remain
  separate from this implementation validation.

### Manual connection-policy matrix — passed 2026-11-03

- [x] All three modes with both transports available; persistence after process restart.
- [x] LAN loss, immediate allowed fallback, return home/new DHCP address, and no P2P-as-LAN label.
- [x] Server unavailable with Wi-Fi still up; bounded alternatives; Server stop/restart.
- [x] Direct group loss versus API-only loss; recovery and no repeated approval prompts.
- [x] Both manual switches, Direct-preferred LAN confirmation/cancel, temporary priority and failure.
- [x] Policy changes during discovery/negotiation/probe; Forget/Stop; no stale status or callbacks.
- [x] Nearby permission denial, Wi-Fi/Location off, approval refusal/timeout, and explicit retry.
- [x] Background, screen-off/paused playback, rebind, and saved settings after restart.
- [x] Developer mode on/off, 100-entry eviction, safe fields, Refresh reading offset/end, Clear.
- [x] Queue-add interrupted after delivery: one factual result, no automatic second append; playback,
  MediaSession, Library/Search/Queue, pairing, and Web UI regressions.

### Phone 0.8.0 release candidate — built and verified 2026-11-03

The clean combined pipeline documented in `RELEASING.md` passed from application-source commit
`9616976a16a8cdf1daf91f3515807b9c7fc6e8bc` in 30 seconds (153 tasks, 152 executed):

- Server debug: **147/147** JVM tests.
- Phone debug: **230/230** JVM tests.
- Phone release: **230/230** JVM tests.
- Total: **607/607** executions, zero failures, errors, or skips.
- Both debug APKs and the release-signed Phone APK assembled successfully.
- Server debug lint: zero errors, 2 warnings. Phone debug/release lint: zero errors, 27 warnings
  each. Existing `getAllNetworks()`/Gradle deprecations and the installed SDK XML advisory remain.
- Phone package `dev.r4remote.poweramp.phone`, `0.8.0` / code 17, min API 26, target/compile 36.
  The manifest retains one `PhoneConnectionService` with `connectedDevice|mediaPlayback`.
- Both bundle APKs verify with Signature Scheme v2 and the same permanent RSA-4096 release signer;
  its SHA-256 fingerprint matches the public Phone `0.7.0` certificate and the historical record
  below. Both pass `zipalign -c -P 16 4`.
- Debug APK signatures/alignment also pass, with the existing Android Debug certificate.
- Server source, root build files, and wrapper are identical to the public source tag. The reused
  Server is byte-for-byte the previous public APK, embedding source `6bb22a8`.
- Phone SHA-256:
  `769b3c4fcaee35383df9fcccbc242a7663f2d4c62ec7b152e3bd65429131551c`.
- Server SHA-256:
  `6a2f1341e7b9e3ae356e954f613415ff41f8189522c4c4c96a161ac357361ed7`.
- Bundle: `outputs/release-0.11.0_phone-0.8.0/`, exactly two APKs, `SHA256SUMS.txt`, `LICENSE`,
  `THIRD_PARTY_NOTICES.md`, and `Apache-2.0.txt`. Checksum verification and all copy comparisons pass.
- No new screenshots were made; README explicitly identifies the retained Phone `0.7.0` screenshots.

The exact signed Phone APK still needs the short in-place check in `RELEASING.md` before
publication. The complete implementation device matrix above is confirmed; signed-candidate
installation has not yet been confirmed. Documentation-only verification commits do not change
the Phone APK-source revision or require another build.

## Implemented product

### Runtime, pairing, and connectivity

- One started-and-bound Server `connectedDevice` foreground service owns all Poweramp integration,
  state, HTTP/WebSocket API, Web UI, LAN NSD, and Wi-Fi Direct publication.
- One Phone `connectedDevice|mediaPlayback` foreground service owns discovery, pairing, network
  connections, reconnect, playback state, Library/Search/Queue requests, and one Media3 session.
- Initial pairing uses an address-free QR with stable Server identity and a short-lived one-time
  secret. Manual persistent Bearer-token pairing remains available for devices without a camera.
- Phone `0.8.0` makes transport preference configurable and adds bounded, authenticated recovery;
  the public Phone `0.7.0` baseline retains its original LAN-first selection.
- Pairing identity is never an IP address. Activities do not own or restart either connection
  runtime.

### Playback and Android integration

- Play, pause, previous, next, absolute seek, shuffle on/off, ratings `0…5`, Like, and Dislike.
- Title, artist, album, artwork, codec, bit depth, sample rate, bitrate, source category, list
  position, playback state, duration, and locally extrapolated progress.
- Player-device `STREAM_MUSIC` volume state/control; Phone never decodes audio or changes its own
  volume.
- One remote Media3 session supplies notification, lock-screen, and compatible Wear OS controls.
- Main Player has an artwork-derived dark theme, cached artwork swipe navigation, retargetable
  control motion, smooth progress, and a verified LAN/Wi-Fi Direct status indicator.

### Library

- Browse All tracks, Artists, Albums, plain/nested Folders, and Playlists through Poweramp's public
  ContentProvider; no Phone database copy is created.
- Server consumes one provider Cursor into a bounded immutable snapshot, closes provider resources,
  and serves lazy `1…100`-row pages through opaque continuation tokens. There is no fixed total-row
  ceiling; lists beyond 1000 rows are verified.
- Track and representative category artwork load lazily through shared bounded memory/disk caches.
- Exact current-track identity is shown for ordinary tracks and exact Queue occurrences; containers
  and ambiguous Playlist identities are never guessed.
- Track lists sort by Poweramp order, title, album, artist, duration, date added, or play count in
  either direction. Sorting applies to the complete snapshot before pagination.
- Loaded content, scroll state, navigation stacks, and sorting preferences survive applicable
  recreation, rebind, and same-Server reconnect paths.

### Search

- Global Search presents independent Tracks, Artists, and Albums sections with independent Show
  more/retry continuation and no artificial result ceiling.
- Comparison normalizes case, spacing, Unicode marks, dash variants, and standalone `and`/`&`;
  bounded Damerau-Levenshtein fallback handles one or two plausible Artist/Album typos.
- Multi-artist membership uses Poweramp's public `multi_artists` relation, so solo and collaboration
  tracks browse under the canonical Artist and related Albums appear in results.
- Explicit `artist - track` and `artist - album` queries use the same verified relations. Track
  fuzzy matching remains intentionally disabled to avoid noise.
- Search has a one-tap clear action and a private removable 20-entry history shown only with focused
  empty input and a visible keyboard.

### Queue

- Queue is displayed in exact Poweramp provider order with distinct entry IDs for repeated tracks;
  an existing occurrence can be selected and identified exactly.
- Queue playback position is presented as `1/N…N/N` only for verified Queue state while API `v1`
  preserves the raw Poweramp value.
- Capability-gated **Add to Queue** works for one row or an ordered selection of up to 100 loaded
  track, Playlist-entry, or Queue-entry rows. Duplicate tracks and exact occurrences are preserved.
- Server revalidates the complete batch through public item URIs, serializes public Queue inserts,
  then sends one documented queue reload broadcast after any successful prefix. Adding does not
  start or change playback.
- Provider batch insertion is not transactional. Responses report requested/added counts and the
  first safe failure; Phone never automatically retries an ambiguous mutation or edits Queue rows
  optimistically.
- Queue removal, clearing, reordering, and Play Next are absent because the audited public Poweramp
  API exposes no supported external contract for them.

### Phone presentation

- Bottom navigation contains Player, Library, Search, and Settings. Queue is an auxiliary Player
  screen rather than a fifth tab.
- Only tab content animates; bottom navigation and the shared Library/Search/Settings mini-player
  remain fixed.
- Composite rows route body tap, long-press selection, overflow action, and Search-history removal
  independently, including recycled views.
- English fallback and Russian resources cover screens, dialogs, scanner, accessibility, service
  notification, and metadata formatting. App language is System default, Russian, or English.
- Settings adds saved transport policy and optional Developer mode/View logs while retaining
  language, About, repository, license, and third-party-notice actions.

### Embedded Web UI

- The English dependency-free same-origin Web UI supports session login, playback, seek, rating,
  Like/Dislike, shuffle, artwork, metadata, and player-device volume.
- Library/Search/Queue remain Phone/API features; the Web UI has no partial library surface.

## Published 0.11.0 / 0.7.0 verification (historical)

### Signed release automation

The published application revision `6bb22a87c77a7563e4855b460c1507b00def1ecc` completed:

```text
clean
:app:testReleaseUnitTest        147/147
:phone:testReleaseUnitTest      211/211
:app:lintRelease
:phone:lintRelease
:app:assembleRelease
:phone:assembleRelease
```

- Total JVM executions: **358/358**, zero failures, errors, or skips.
- Both release lint tasks pass with zero errors. Server retains two dependency/tool update
  advisories; Phone retains 26 non-fatal layout/performance/RTL/resource/dependency suggestions.
- Server package: `dev.r4remote.poweramp`, version `0.11.0` / code 14.
- Phone package: `dev.r4remote.poweramp.phone`, version `0.7.0` / code 16.
- Both retain min API 26, target/compile API 36, one expected foreground service, and 16 KiB-aware
  ZIP alignment.
- Both verify with APK Signature Scheme v2 and one permanent RSA-4096 signer,
  `CN=Poweramp Remote Release`.
- Signing-certificate SHA-256:
  `C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.
- Candidate APK SHA-256:
  - Server: `6a2f1341e7b9e3ae356e954f613415ff41f8189522c4c4c96a161ac357361ed7`;
  - Phone: `5c7aa5ad089e2c6e45b444f2ec6f7e5857a068f63d5411b006412436aa137a8c`.
- Both APKs embed VCS revision `6bb22a87c77a7563e4855b460c1507b00def1ecc`.

### Real-device validation

The maintainer installed the exact signed published APKs over public Server `0.10.2` and Phone
`0.6.0` and confirmed the complete [`RELEASING.md`](RELEASING.md) matrix. The established test setup
uses a Hiby R4 on Android 12 for Server/Poweramp and a Samsung Galaxy S24 Ultra on Android 16 for
Phone.

Confirmed areas include:

- in-place version update and preservation of Server token/identity and Phone pairing/credential;
- Player, Player devices, Library, Search, Queue, Settings, About, language, insets, keyboard,
  navigation, Back, recreation, reconnect, background, screen-off, and restart behavior;
- All tracks/Artists/Albums/Folders/Playlists, long paging, artwork, sorting, exact playback, current
  indicators, empty/error/retry states, and unchanged Poweramp follow-on order;
- categorized Search relevance, punctuation/diacritic/typo handling, multi-artist membership,
  related Albums, structured queries, independent continuation, history, and no 1000-row limit;
- Queue empty/multi-page/duplicate behavior, exact entry playback, `1/N…N/N`, Queue exit, single
  and ordered batch additions, duplicate preservation, and refresh without playback restart;
- playback controls, rating/shuffle, volume, artwork transitions, MediaSession, notification,
  lock-screen, Wear OS, LAN, Wi-Fi Direct fallback/recovery, QR/manual pairing, and Web UI.

## Known limitations

- Local HTTP/WebSocket traffic is authenticated but not encrypted. Use only trusted networks and do
  not expose port `8765` to the internet.
- Pairing persistence currently stores one Server; full multi-player selection is future work.
- Final signed Phone `0.8.0` in-place validation remains pending; the implementation's full device
  matrix is confirmed.
- Wi-Fi Direct behavior still varies by Android/OEM permissions, Location Mode, approval, and
  group-owner selection.
- Queue removal, Clear, Reorder, and Play Next are unavailable; Scoped Search and Lyrics are
  deferred.
- `bitRate` units and non-Queue `positionInList` index base remain Poweramp-defined uncertainties;
  API `v1` preserves their raw values.
- Library edit visibility is snapshot-based: Reload begins a fresh snapshot. A successful local
  Queue add invalidates only Queue and triggers its fresh load.
- Broader Android vendor/Poweramp version coverage remains useful but is not a blocker for the
  validated release.

## Release handoff

Server `0.11.0` / Phone `0.7.0` are published; their source tag and binaries remain unchanged.
The verified bundle is prepared at `outputs/release-0.11.0_phone-0.8.0/`. Local integration uses a
fast-forward from `codex/connection-policy` to `main`; nothing is pushed or published by this task.
After the exact signed Phone passes the in-place check, the maintainer can push `main` and create
`server-v0.11.0_phone-v0.8.0` at `9616976a16a8cdf1daf91f3515807b9c7fc6e8bc`, then publish the six
bundle files with the description from `RELEASE_NOTES.md`. The reused Server keeps its original
source revision `6bb22a8`; a new Server build is unnecessary.

Future implementation order is maintained in [`ROADMAP.md`](ROADMAP.md); durable architecture and
protocol contracts are maintained in [`PROJECT.md`](PROJECT.md).
