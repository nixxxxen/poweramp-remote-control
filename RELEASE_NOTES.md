# Poweramp Remote — Phone UI release

Poweramp Remote controls Poweramp running on another Android device over a local connection. Audio
stays on the player device; no cloud service or audio streaming is involved.

## Versions

- Server `0.10.2` (`versionCode 13`, unchanged)
- Phone Client `0.6.0` (`versionCode 15`)
- local API `v1` (unchanged)

## What is new

- Artwork now drives a dark animated palette and transitions smoothly between tracks.
- Swipe the artwork for Previous/Next; recently confirmed neighboring covers are cached for a more
  immediate carousel transition.
- Play/Pause, Previous/Next, Like/Dislike, and Shuffle have refined motion and pressed feedback.
- Playback progress moves smoothly from the existing event-driven remote position without adding
  network polling.
- The main player has a compact LAN/Wi-Fi Direct connection indicator and stable muted metadata-chip
  colors for formats, bit depth, sample rate, and bitrate.

## Upgrade and compatibility

Install Phone Client `0.6.0` directly over public Phone `0.5.0`. The application ID and permanent
release certificate are unchanged, so pairing and the saved Bearer credential remain intact. Server
`0.10.2` requires no update. LAN/NSD, automatic Wi-Fi Direct fallback, QR/manual pairing,
MediaSession, notification/lock-screen/Wear controls, Web UI, and API `v1` are unchanged.

Android 8.0 or newer is required. Library, Queue, Lyrics, audio streaming, and full multi-player
selection are not included. Local HTTP/WebSocket traffic is authenticated but unencrypted; use it
only on trusted networks.

## Release assets

- `Poweramp-Remote-Server-v0.10.2.apk`
- `Poweramp-Remote-Phone-v0.6.0.apk`
- `SHA256SUMS.txt`
- license and third-party notice files

Poweramp Remote is independent and is not affiliated with Poweramp or Max MP.
