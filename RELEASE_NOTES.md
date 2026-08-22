# Poweramp Remote — second public release

Poweramp Remote lets one Android device control Poweramp running on another device over a local
network, without a cloud service or audio streaming.

## Versions

- Server `0.10.2` (`versionCode 13`)
- Phone Client `0.5.0` (`versionCode 14`)
- local API `v1` (unchanged)

## What is new

- Phone Client now has a compact main menu with **Settings** and **About**.
- Settings provides a persistent app-language choice: **System default**, **Russian**, or
  **English**. System default uses Russian only when Russian is the primary system language and
  English for every other language.
- The selected language applies immediately to all Phone screens, dialogs, scanner prompts,
  accessibility text, foreground notification, and notification channel. Android 13 and newer
  also expose English and Russian in system app-language settings.
- Phone Client now has complete English fallback resources and a complete Russian translation,
  including locale-aware metadata units and decimal separators.
- About shows package-derived version information, API v1, the public repository, MIT License,
  third-party notices, and the project-independence notice.
- Server Activity, foreground notification/channel, pairing instructions, and the embedded Web UI
  are now entirely English. The Web UI declares `lang="en"` and includes English status, control,
  login, metadata, and accessibility copy.

## Upgrade compatibility

Server `0.10.2` and Phone Client `0.5.0` use the same application IDs and permanent release
certificate as public Server `0.10.1` and Phone Client `0.4.2`. Install each new APK directly over
the corresponding first-release APK. Do not uninstall the public release: Server identity, API
token, saved Server identity, Bearer credential, and pairing are designed to remain intact, so no
new pairing is required.

Earlier pre-release APKs were debug-signed and remain a separate historical boundary. They cannot
be updated by these release-signed APKs; uninstalling those debug builds is still required and
clears their local pairing state.

## Preserved behavior

- one Server foreground service and one Poweramp integration path;
- one Phone connection/MediaSession foreground service;
- LAN/NSD preference with automatic Wi-Fi Direct fallback and LAN recovery;
- one-time QR pairing and manual Bearer-token fallback;
- playback, seek, player-device volume, artwork, detailed metadata, rating, Like/Dislike, and
  shuffle controls;
- Android MediaSession, notification/lock-screen integration, compatible Wear OS controls, and
  background reconnect;
- unchanged API v1 routes, JSON fields, authentication, status semantics, and raw Poweramp bitrate
  and list-position values.

## Requirements and limitations

Android 8.0 or newer is required on both devices, and Poweramp must be installed on the Server
device. Wi-Fi Direct availability and approval depend on Android and the device manufacturer.
Library, Queue, Lyrics, audio streaming, and full multi-player persistence are not included. Local
HTTP/WebSocket traffic is authenticated but unencrypted, so use it only on trusted networks.

## Release assets

- `Poweramp-Remote-Server-v0.10.2.apk`
- `Poweramp-Remote-Phone-v0.5.0.apk`
- `SHA256SUMS.txt`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `Apache-2.0.txt`

## Download verification

Both APKs must be signed by the same permanent release certificate (`CN=Poweramp Remote Release`),
whose SHA-256 fingerprint is
`C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.
Use the attached `SHA256SUMS.txt` to verify the downloaded files. APK checksums are generated only
from the final release-signed files and are intentionally not hard-coded in this source document.

Poweramp Remote is independent and is not affiliated with, endorsed by, sponsored by, or supported
by Poweramp or Max MP.
