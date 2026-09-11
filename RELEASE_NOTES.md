# Poweramp Remote — Library, Search, and Queue release

Poweramp Remote controls Poweramp running on another Android device over a local connection. Audio
stays on the player device; no cloud service or audio streaming is involved.

## Versions

- Server `0.11.0` (`versionCode 14`)
- Phone Client `0.7.0` (`versionCode 16`)
- local API `v1` (backward-compatible)

## What is new

- Phone now has dedicated Player, Library, Search, and Settings tabs. Library/Search/Settings share
  a fixed mini-player, while only the tab content participates in navigation animation.
- Library browses All tracks, Artists, Albums, Folders, and Playlists through lazy bounded pages.
  Track lists support Poweramp order, title, album, artist, duration, date added, and play count in
  both directions.
- Global Search separates Tracks, Artists, and Albums, continues every category independently,
  supports tolerant entity matching and structured `artist - title/album` queries, and keeps a
  removable local query history.
- Track and category rows use lazy cached artwork. The exact currently playing track is marked when
  the Server provides a verified underlying identity.
- Queue can be viewed in Poweramp order, including duplicate occurrences, and any existing entry
  can be selected exactly. Queue position is presented as `1/N…N/N` without changing raw API data.
- **Add to Queue** is available for one track or an ordered selection of up to 100 loaded track,
  playlist-entry, or Queue-entry rows. Duplicate tracks are preserved and playback is not started
  by adding them.
- Settings and About use a more concise presentation without redundant explanatory copy.

## Upgrade and compatibility

Install Server `0.11.0` directly over public Server `0.10.2` and Phone Client `0.7.0` directly over
public Phone `0.6.0`. The application IDs and permanent release certificate remain unchanged, so
the Server identity/API token and the Phone's saved pairing/Bearer credential are preserved. Both
new APKs are required for the complete Library/Search/Queue feature set.

API `v1` remains backward-compatible. An older Server continues to support the earlier player UI;
capability-gated Library sorting and Queue-add controls stay hidden when unsupported. LAN/NSD,
automatic Wi-Fi Direct fallback, QR/manual pairing, MediaSession, notification/lock-screen/Wear
controls, player-device volume, and the embedded Web UI retain their existing architecture.

Android 8.0 or newer is required. Queue removal, clearing, reordering, and Play Next are not
included because the audited public Poweramp API does not document those mutations. Lyrics, audio
streaming, and full multi-player selection are also not included. Local HTTP/WebSocket traffic is
authenticated but unencrypted; use it only on trusted networks.

## Release assets

- `Poweramp-Remote-Server-v0.11.0.apk`
- `Poweramp-Remote-Phone-v0.7.0.apk`
- `SHA256SUMS.txt`
- license and third-party notice files

Poweramp Remote is independent and is not affiliated with Poweramp or Max MP.
