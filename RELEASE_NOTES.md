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

## Download verification

Both APKs are signed by the same permanent release certificate (`CN=Poweramp Remote Release`),
whose SHA-256 fingerprint is
`C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.
Use the attached `SHA256SUMS.txt` and the checksums in the GitHub Release description to verify
downloaded files. `LICENSE`,
`THIRD_PARTY_NOTICES.md`, and `Apache-2.0.txt` accompany the binary release.

## Required fresh install for pre-release testers

Earlier test APKs used Android debug certificates. These release-signed APKs cannot be installed as
updates over those builds. Uninstall both old debug apps first, install the two release APKs, and
pair Server and Phone again. Uninstalling clears each app's local credentials and pairing state.

Poweramp Remote is independent and is not affiliated with, endorsed by, sponsored by, or supported
by Poweramp or Max MP.
