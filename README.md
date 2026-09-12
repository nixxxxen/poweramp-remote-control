# Poweramp Remote

Poweramp Remote is an independent, open-source remote control for Poweramp on Android. It keeps
music playback on the player device while a second Android phone, a browser, Android system media
controls, or a compatible Wear OS controller operates it over a local connection.

Current versions:

- Server `0.11.0` (`versionCode 14`)
- Phone Client `0.7.0` (`versionCode 16`)
- local API `v1`

The Server and Phone Client are separate APKs with independent version numbers.

## Architecture

| Component | Runs on | Responsibility |
| --- | --- | --- |
| Server | Android device that runs Poweramp | Uses Poweramp's public Intent API, exposes the authenticated local API and Web UI, publishes LAN/Wi-Fi Direct discovery, and controls player-device volume. |
| Phone Client | A second Android phone | Owns discovery, pairing, reconnect, the remote player UI, and one Android MediaSession. It controls the Server and never receives or decodes audio. |

The Phone Client discovers `_poweramp-remote._tcp` with LAN NSD first. If the paired player is not
available on the LAN, it can automatically start the existing Wi-Fi Direct discovery path. Android
and OEM connection approval dialogs are always respected; LAN remains preferred when it returns.

## Features

- **Playback and volume:** play/pause, previous/next, seek, shuffle, ratings, detailed metadata,
  artwork, and the player device's media volume, with Android MediaSession/Wear controls.
- **Library:** browse all tracks, artists, albums, folders, and playlists with lazy artwork and
  full-list sorting.
- **Search:** categorized Tracks, Artists, and Albums results, typo-tolerant matching, structured
  artist/title queries, independent pagination, and removable local history.
- **Queue:** view and play exact Queue occurrences, including duplicates, and add one track or an
  ordered batch of up to 100 tracks.
- **Web UI:** a small authenticated browser remote for essential playback, seek, rating, shuffle,
  artwork, metadata, and volume controls.

## Screenshots

<table>
  <tr>
    <th>Phone Client — Now playing</th>
    <th>Phone Client — Library</th>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/phone-now-playing.jpg"
           alt="Phone Client now playing screen"
           width="360">
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/phone-library.jpg"
           alt="Phone Client library screen"
           width="360">
    </td>
  </tr>
  <tr>
    <th>Phone Client — Search</th>
    <th>Phone Client — Player devices</th>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/phone-search.jpg"
           alt="Phone Client search screen"
           width="360">
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/phone-player-devices.jpg"
           alt="Phone Client player devices screen connected through LAN"
           width="360">
    </td>
  </tr>
</table>

## Requirements

- Android 8.0 (API 26) or newer on both devices;
- Poweramp installed on the Server/player device;
- Wi-Fi connectivity for LAN operation; Wi-Fi Direct support on both devices for fallback;
- notification and nearby-device permissions where requested;
- Location permission and Location Mode may be required for Wi-Fi Direct on Android 8–12L;
- a camera only for QR scanning—manual Bearer pairing remains available without one.

Wi-Fi Direct behavior varies by Android version and OEM. The Server/player device must become the
group owner for the current fallback connection to work.

## Installation and pairing

Download the two APKs from the [latest GitHub Release](../../releases/latest):

- `Poweramp-Remote-Server-v0.11.0.apk`
- `Poweramp-Remote-Phone-v0.7.0.apk`

The release also provides `SHA256SUMS.txt` plus the project and third-party license notices.

> **Library and Queue update:** install Server `0.11.0` over public Server `0.10.2` and Phone Client
> `0.7.0` over public Phone `0.6.0` without uninstalling. Both application IDs and the permanent
> release certificate are unchanged, so Server identity, API token, saved pairing, and Bearer
> credential remain in place. Both new APKs are required for the complete feature set.

> **Historical debug-build boundary:** pre-release APKs were signed with Android debug
> certificates. Release-signed APKs still cannot update those installations. Uninstall both old
> debug apps before installing a public release; uninstalling removes local credentials and pairing
> preferences, so pair Server and Phone again afterward.

Then:

1. Install the Server APK on the Android device that runs Poweramp. Open it, grant the requested
   permissions, and start the Server.
2. Install the Phone Client APK on the controlling phone and grant its requested permissions.
3. Keep both devices on the same LAN, open **Player devices**, choose **Pair new player**, and scan
   the QR code shown by the Server.
4. If QR scanning is unavailable, use the Server's explicit credential-copy action, choose
   **Enter token manually** on the Phone, and paste the Bearer token. Manual pairing discovers and
   verifies the Server on the LAN.

After successful pairing, the Phone Client stores the Server identity and credential in its private,
backup-excluded app storage and reconnects automatically. If LAN discovery fails, the client may
offer or start Wi-Fi Direct fallback; confirm any system dialogs shown on either device.

The Phone bottom bar opens **Player**, **Library**, **Search**, and **Settings**. The connection pill
opens **Player devices**, and the Queue button in the Player toolbar opens the current Poweramp
Queue. Settings selects **System default**, **Russian**, or **English** without changing pairing or
stopping the connection service; About shows installed version metadata, licenses, and project
links.

For browser control, open `http://<SERVER-IP>:8765/` from a device on the same trusted LAN and log
in with the credential copied through the Server's explicit Web UI credential action.

## Security

Poweramp Remote is local-first and has no cloud service. The QR code contains the stable Server ID,
API version, and a short-lived one-time pairing secret; it does not contain the persistent Bearer
credential or an IP address. A successful exchange invalidates the one-time secret.

The local API uses Bearer authentication, but HTTP and WebSocket traffic is not encrypted with TLS.
Use the project only on networks you trust. Do not port-forward the Server, expose port `8765` to
the internet, publish credentials or QR codes, or include pairing data in bug reports. Forget or
re-pair the player if a credential may have been exposed.

## Known limitations

- Audio stays on the player device; this project is a controller, not an audio-streaming client.
- Queue removal, clearing, reordering, and Play Next are not implemented because the audited public
  Poweramp API does not document those mutations. Lyrics are not implemented.
- Persistence currently targets one paired Server; full multi-player selection is not implemented.
- Wi-Fi Direct depends on platform/OEM support, permissions, Location Mode on older Android, group
  owner selection, and user-approved system prompts.
- Wear OS support is provided through the Phone Client's Android MediaSession; there is no separate
  Wear app.
- Some optional metadata depends on the installed Poweramp version and the current track.
- Local HTTP/WebSocket traffic is authenticated but unencrypted.

See the [roadmap](ROADMAP.md) for planned work and explicit non-goals.

## Development

The project uses Gradle Wrapper, JDK 17, and Android SDK 36. A full local verification run is:

```shell
./gradlew clean \
  :app:testDebugUnitTest :phone:testDebugUnitTest \
  :app:lintDebug :phone:lintDebug \
  :app:assembleDebug :phone:assembleDebug
```

OpenAI Codex is used as the primary coding agent for this project. Architecture, requirements,
product decisions, and real-device validation are directed and performed by a human; much of the
implementation, tests, refactoring, and documentation is produced collaboratively with Codex and
reviewed against those human-defined requirements.

Release APKs must use a permanent release signing key stored outside the repository. Maintainer
instructions and the fresh-install implications of the first public key are documented in
[`RELEASING.md`](RELEASING.md).

## License and attribution

Original Poweramp Remote code is available under the [MIT License](LICENSE). Poweramp API-derived
portions and bundled dependencies retain their own terms; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the [`licenses`](licenses/) directory.

Poweramp Remote is an independent open-source project. It is not affiliated with, endorsed by,
sponsored by, or supported by Poweramp or Max MP. Poweramp and related names and logos are the
property of their respective owners and are used only to describe compatibility.

> Google Play Protect may classify a sideloaded release as an “Uncommon” app. This warning does not
> by itself indicate a detected malware category. Download APKs only from this repository and verify
> them against `SHA256SUMS.txt` before installation.
