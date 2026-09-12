# Release checklist

This repository does not publish releases automatically. Both Gradle `release` build types use one
maintainer-supplied signing identity loaded from a private properties file outside the repository.
Any release task fails before building if that file is absent or incomplete; debug builds continue
to use Android's separate debug signing configuration.

## Repository and key safety

1. Confirm the protected pre-rewrite mirror backup is still available. Never publish local
   `refs/codex/*` or use `git push --mirror`.
2. Repeat the secret and generated-file scan from a fresh clone of the exact history that will be
   published.
3. Keep the permanent Android release key outside the repository and backed up securely in more
   than one protected location. Never commit the keystore, aliases, or passwords.
4. Run the complete tests, lint, and clean release build for both modules.

## Signing

Create the permanent keystore outside the repository. Store its keystore password, key password,
and alias in a password manager. Keep at least one additional protected copy of the keystore on a
separate encrypted device or storage account, and test that the backup can be opened before the
first release.

Create a private properties file outside the repository with these four entries:

```properties
storeFile=/secure/path/to/poweramp-remote-release.jks
storePassword=<private keystore password>
keyAlias=<private key alias>
keyPassword=<private key password>
```

Never commit that file, pass passwords on a command line, or put them in build logs. Point Gradle
to the file by path only, using either the `powerampRemoteSigningProperties` project property or
the `POWERAMP_REMOTE_SIGNING_PROPERTIES` environment variable.

Build and verify both signed release variants with the pinned Gradle Wrapper:

```shell
./gradlew -PpowerampRemoteSigningProperties=/secure/path/signing.properties clean \
  :app:testReleaseUnitTest :phone:testReleaseUnitTest \
  :app:lintRelease :phone:lintRelease \
  :app:assembleRelease :phone:assembleRelease
```

Gradle signs the APKs during packaging; do not modify either APK afterward. Use the Android SDK's
`apksigner verify --verbose --print-certs` to validate the completed files without exposing the
private key or passwords.

Create exactly these public artifacts:

- `Poweramp-Remote-Server-v0.11.0.apk`
- `Poweramp-Remote-Phone-v0.7.0.apk`
- `SHA256SUMS.txt`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `Apache-2.0.txt`

For each APK, verify:

- APK signature and certificate SHA-256 with `apksigner verify --verbose --print-certs`;
- package ID, `versionName`, and `versionCode` with Android SDK tooling;
- installation and startup on a clean Android device;
- QR pairing, manual pairing, reconnect, playback controls, notification/MediaSession, artwork,
  seek, rating/shuffle, and volume on real devices;
- a published SHA-256 file checksum.

Generate `SHA256SUMS.txt` only after the release commit and final APK build. Android Gradle Plugin
embeds the source commit revision in each APK, so hard-coding an APK checksum into that same source
commit would create a self-reference and change the APK on the next build.

Distribute `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `licenses/Apache-2.0.txt` with the APKs. The
release tag should be created only after the final history decision and may be signed separately
from the APKs.

For Server `0.11.0` / Phone `0.7.0`, the ready-to-upload local bundle belongs in
`outputs/release-0.11.0_phone-0.7.0/`. The directory is intentionally ignored by Git.

## Mandatory subsequent-release in-place pass

Every release after the first public release must prove update compatibility with the exact prior
public release. A fresh install is useful additional coverage but does not replace this matrix.
Build the exact release-signed APKs from the final release commit with the same permanent signing
certificate, install those exact files, and record device models and Android versions.

For Server `0.11.0` / Phone Client `0.7.0`, every item below has been completed with the exact
release APKs intended for upload:

1. Install Server `0.11.0` over public Server `0.10.2` without uninstalling it. Confirm that Server
   identity, API token, browser/API access, and pairing state remain intact.
2. Install Phone `0.7.0` over public Phone `0.6.0` without uninstalling it. Confirm that the saved
   Server identity and Bearer credential remain intact and reconnect without re-pairing.
3. Check Player, Library, Search, Queue, Player devices, Settings, About, dialogs, scanner,
   foreground notification, and notification actions in **System default**, **Russian**, and
   **English**, including compact screens, rotation, keyboard/insets, Back, and rapid tab changes.
4. Browse All tracks, Artists, Albums, nested Folders, and Playlists. Confirm complete paging beyond
   1000 rows, representative/track artwork, exact current-track indicators, container Back state,
   reload, reconnect/rebind retention, and empty/error/retry states.
5. Exercise every Library sorting criterion in both directions for applicable list types. Confirm
   stable full-list ordering, null-last behavior, preference restoration, and unchanged exact
   Playlist-entry playback.
6. Exercise Global Search Tracks/Artists/Albums, exact/prefix/substring and typo-tolerant matching,
   multi-artist relations, `artist - title/album`, independent Show more, history open/remove/clear,
   keyboard visibility, result navigation/Back restoration, and no artificial row limit.
7. Exercise Queue empty/multi-page/duplicate states, exact occurrence playback/current indicator,
   `1/N…N/N`, Previous/Next, automatic exit from Queue, Reload, reconnect/rebind, and return to the
   retained Player.
8. Add one track from All tracks, Artist, Album, Folder, Global Search, Playlist, and Queue. Confirm
   no playback restart, exact Queue refresh/order, duplicate preservation, and old-Server hiding of
   unsupported controls.
9. Exercise ordered batch Add to Queue, the 100-item cap, selection order, repeated underlying
   tracks, selection/Back/pagination/recreation/reconnect, double-submit suppression, and honest
   partial/provider-failure presentation without automatic retry.
10. Recheck artwork palettes/transitions/swipes, Play/Pause, Previous/Next, seek, Like/Dislike,
    Shuffle, player-device volume, metadata chips, LAN/Wi-Fi Direct recovery, background/screen-off
    operation, MediaSession, notification/lock-screen, compatible Wear OS, and Web UI controls.
11. Restart both applications and devices; confirm pairing, language, sorting preferences, Search
    history, playback state, and automatic reconnect survive as designed.
12. Confirm Queue removal/clear/reorder/Play Next, Scoped Search, Lyrics, audio streaming, and full
    multi-player selection remain absent rather than partially exposed.

Only the exact release-signed APKs intended for upload count as validated. Any failure remains a
release blocker until understood, fixed or explicitly documented, and re-tested.

The maintainer completed this matrix on 2026-09-12 with Server on a Hiby R4 running Android 12 and
Phone Client on a Samsung Galaxy S24 Ultra running Android 16. Both APKs were built from
`6bb22a87c77a7563e4855b460c1507b00def1ecc`, retain the permanent release certificate, and have the
hashes recorded in `STATUS.md`, `RELEASE_NOTES.md`, and the staged `SHA256SUMS.txt`. The release tag
`server-v0.11.0_phone-v0.7.0` must point to that APK-source commit. Later documentation-only commits
do not require rebuilding the already validated binaries.

## Publishing Server 0.11.0 / Phone 0.7.0

1. Fast-forward `main` from `codex/library-queue` and push it.
2. Create `server-v0.11.0_phone-v0.7.0` at application-source revision `6bb22a8` and push the tag.
3. Create a GitHub Release from that tag and copy the body from `RELEASE_NOTES.md`.
4. Upload every file from `outputs/release-0.11.0_phone-0.7.0/` without renaming it.
5. Download or otherwise re-read the uploaded assets and confirm `SHA256SUMS.txt` plus the two APK
   signatures before publishing the Release.

## Historical first-public-release fresh-install pass

Do not publish solely on the strength of unit tests, lint, or APK verification. Record the device
models and Android versions used, and complete this fresh-install matrix with the exact
release-signed APKs that will be uploaded:

1. Uninstall both old debug-signed Server and Phone apps.
2. Install and start the release Server on the Poweramp device.
3. Install and start the release Phone Client on the controlling phone.
4. Complete QR pairing and confirm authenticated state/WebSocket updates.
5. Forget or reset the pairing, then complete manual Bearer-token pairing as the fallback path.
6. Confirm ordinary LAN/NSD discovery, connection, and recovery.
7. Make the paired Server unavailable through LAN and confirm automatic LAN to Wi-Fi Direct
   fallback, including required Android/OEM approval; restore LAN and confirm it becomes preferred.
8. Confirm Server and Phone operation while backgrounded and with both screens off.
9. Confirm artwork and all expected track metadata update on track changes.
10. Confirm Play, Pause, Previous, and Next.
11. Confirm seek in both the Phone UI and supported system media controls.
12. Confirm Like, Dislike, and Shuffle state/control.
13. Confirm remote player-device media volume and player-side volume updates.
14. Confirm MediaSession notification and lock-screen metadata, position, and controls.
15. Confirm controls from a compatible Wear OS device through the Phone MediaSession.
16. Restart both apps and then both devices; confirm saved pairing and automatic reconnect.

This 16-step fresh-install matrix was the mandatory boundary for the first public release. Its
successful result remains recorded in `STATUS.md` and `RELEASE_NOTES.md`; later releases use the
in-place matrix above while retaining fresh install as additional coverage.

## Existing debug installations

Previously produced APKs use Android debug certificates, including an older certificate whose
private key is no longer available. A new permanent release key cannot update those installations,
even though the application IDs are retained. Unless an appropriate previous production key is
recovered, the first public release is a fresh-install boundary: testers must uninstall the old
debug build before installing the release APK, which removes that app's local pairing state.

Do not use an Android debug key as the permanent public release key.
