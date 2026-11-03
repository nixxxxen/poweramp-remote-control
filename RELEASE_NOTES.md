# Poweramp Remote — connection control and recovery

Poweramp Remote controls Poweramp on another Android device over a local connection. Audio stays
on the player device; no cloud service or audio streaming is involved.

## Versions

- Phone Client `0.8.0` (`versionCode 17`)
- Server `0.11.0` (`versionCode 14`, unchanged)
- local API `v1` (unchanged)

## What is new

- Choose how to connect: LAN only, prefer LAN with Wi-Fi Direct fallback (the default), or prefer
  Wi-Fi Direct with LAN fallback. Preferences survive app and device restarts.
- Switch to LAN or Wi-Fi Direct from Player devices. A manual choice temporarily takes priority;
  switching to LAN while Direct is preferred asks for confirmation.
- Keep a healthy Direct connection when home Wi-Fi becomes available. After Server stop/restart,
  Direct preference is applied again; a usable existing P2P group can recover without renegotiation.
- More reliable recovery after network loss, Server unavailability, changed addresses, and stale
  discovery callbacks. Attempts have bounded deadlines and respect Android approval requirements.
- Connection status reflects the authenticated API connection. P2P discovery cannot be mistaken
  for a LAN connection, and a replacement is confirmed before a healthy connection is released.
- Optional Developer mode adds a viewer for the latest 100 connection events, with Refresh and
  Clear. The buffer exists only in process memory and is cleared when the mode is disabled.

## Upgrade and compatibility

Install Phone Client `0.8.0` over public Phone `0.7.0` without uninstalling it. The application ID
and permanent release certificate are unchanged, preserving pairing, credentials, language, and
app data. Server `0.11.0` needs no update; its APK in this release is the exact previously published
binary. A new Phone installation defaults to LAN preference with Direct fallback.

Library, Search, Queue additions, playback, volume, QR/manual pairing, MediaSession,
notification/lock-screen/Wear controls, and Web UI remain available. Network recovery never
automatically replays Queue additions or other ambiguous commands.

Android 8.0 or newer is required. Wi-Fi Direct remains subject to device support, permissions,
group-owner selection, and system approval. Local HTTP/WebSocket traffic is authenticated but
unencrypted; use trusted networks.

## Verification

The complete connection-policy device matrix and Direct-preferred reconnect correction were
confirmed by the maintainer on 2026-11-03. Exact build provenance and final signed-candidate
validation are recorded separately in `STATUS.md` and `RELEASING.md`.

The combined clean debug/release pipeline passed 607 JVM test executions with zero failures;
lint passed with zero errors. Both APKs verify with the permanent release certificate and pass
16 KiB-aware ZIP alignment. Phone was built from `9616976`; the unchanged Server retains `6bb22a8`.

## Release assets

- `Poweramp-Remote-Phone-v0.8.0.apk`
- `Poweramp-Remote-Server-v0.11.0.apk` (unchanged)
- `SHA256SUMS.txt`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `Apache-2.0.txt`

Verify the APKs against `SHA256SUMS.txt` after download.

- Phone SHA-256: `769b3c4fcaee35383df9fcccbc242a7663f2d4c62ec7b152e3bd65429131551c`
- Server SHA-256: `6a2f1341e7b9e3ae356e954f613415ff41f8189522c4c4c96a161ac357361ed7`

Poweramp Remote is independent and is not affiliated with Poweramp or Max MP.
