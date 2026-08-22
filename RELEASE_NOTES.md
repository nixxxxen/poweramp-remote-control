# Poweramp Remote — first public release

Poweramp Remote lets one Android device control Poweramp running on another device over a local
network, without a cloud service or audio streaming.

## Versions

- Server `0.10.1`
- Phone Client `0.4.2`
- local API `v1`

## Highlights

- native Server and Phone Client with an embedded browser UI;
- automatic LAN/NSD discovery with Wi-Fi Direct fallback;
- address-free, one-time QR pairing plus manual Bearer-token fallback;
- playback, seek, player-device volume, artwork, detailed metadata, rating, Like/Dislike, and
  shuffle controls;
- Android MediaSession, media notification/lock-screen integration, and compatible Wear OS
  controls;
- background reconnect and event-driven playback updates.

## Requirements and limitations

Android 8.0 or newer is required on both devices, and Poweramp must be installed on the Server
device. Wi-Fi Direct availability and approval depend on Android and the device manufacturer.
Library, Queue, Lyrics, audio streaming, and full multi-player persistence are not included. Local
HTTP/WebSocket traffic is authenticated but unencrypted, so use it only on trusted networks.

## APKs

- `Poweramp-Remote-Server-v0.10.1.apk`
- `Poweramp-Remote-Phone-v0.4.2.apk`

Poweramp Remote is independent and is not affiliated with, endorsed by, sponsored by, or supported
by Poweramp or Max MP.
