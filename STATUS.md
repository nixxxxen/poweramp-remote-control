# Current project status

## Release state

- Public baseline: Server `0.10.2` / code 13 and Phone `0.6.0` / code 15.
- Fully validated release candidate: Server `0.11.0` / code 14 and Phone `0.7.0` / code 16.
- Local API: backward-compatible `v1`.
- Validated application-source revision: `6bb22a8`
  (`6bb22a87c77a7563e4855b460c1507b00def1ecc`).

The candidate has passed its complete signed in-place device matrix. Both APKs install directly
over the public versions with the permanent certificate and preserve Server identity, API token,
Phone pairing, Bearer credential, language, and application data. Documentation cleanup may follow
as a documentation-only commit; the release tag must continue to identify the exact application-
source revision embedded in the published APKs.

## Implemented product

### Runtime, pairing, and connectivity

- One started-and-bound Server `connectedDevice` foreground service owns all Poweramp integration,
  state, HTTP/WebSocket API, Web UI, LAN NSD, and Wi-Fi Direct publication.
- One Phone `connectedDevice|mediaPlayback` foreground service owns discovery, pairing, network
  connections, reconnect, playback state, Library/Search/Queue requests, and one Media3 session.
- Initial pairing uses an address-free QR with stable Server identity and a short-lived one-time
  secret. Manual persistent Bearer-token pairing remains available for devices without a camera.
- LAN remains preferred. The exact paired Server is discovered through Wi-Fi Direct only as
  fallback, with mandatory Android/OEM permissions, Location Mode, approval, and group-owner
  behavior preserved.
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
- Settings/About use concise labels while retaining repository, license, and third-party-notice
  actions.

### Embedded Web UI

- The English dependency-free same-origin Web UI supports session login, playback, seek, rating,
  Like/Dislike, shuffle, artwork, metadata, and player-device volume.
- Library/Search/Queue remain Phone/API features; the Web UI has no partial library surface.

## Verification

### Signed release automation

The exact candidate revision `6bb22a87c77a7563e4855b460c1507b00def1ecc` completed:

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

The maintainer installed the exact signed candidate APKs over public Server `0.10.2` and Phone
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
- LAN is currently always preferred over Wi-Fi Direct. User-controlled transport policy is the
  next planned feature.
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

Application code is ready. Remaining release work is procedural:

1. push the final documentation-only commit to the remote feature branch;
2. fast-forward `main` without altering the validated application-source commit;
3. create `server-v0.11.0_phone-v0.7.0` at `6bb22a8`;
4. publish the GitHub Release using `RELEASE_NOTES.md` and every file from the prepared
   `outputs/release-0.11.0_phone-0.7.0/` bundle.

Future implementation order is maintained in [`ROADMAP.md`](ROADMAP.md); durable architecture and
protocol contracts are maintained in [`PROJECT.md`](PROJECT.md).
