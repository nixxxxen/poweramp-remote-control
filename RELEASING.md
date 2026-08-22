# Release checklist

This repository does not publish or sign releases automatically. The current Gradle `release`
build types produce unsigned APKs until a maintainer supplies an external signing step or a secure
signing configuration.

## Before the first public release

1. Decide whether to rewrite the private Git history to remove generated build artifacts and the
   author/committer email documented in `STATUS.md`. Make a protected mirror backup first. Never
   publish local `refs/codex/*` or use an unreviewed `git push --mirror`.
2. Repeat the secret and generated-file scan from a fresh clone of the exact history that will be
   published.
3. Create a dedicated, permanent Android release key outside the repository. Back it up securely
   in more than one protected location. Never commit the keystore, aliases, or passwords.
4. Decide and document the signing-key migration boundary described below.
5. Run the complete tests, lint, and clean release build for both modules.

## Signing

Build the unsigned release packages with the pinned Gradle Wrapper:

```shell
./gradlew clean \
  :app:testReleaseUnitTest :phone:testReleaseUnitTest \
  :app:lintRelease :phone:lintRelease \
  :app:assembleRelease :phone:assembleRelease
```

Use the Android SDK's `zipalign` and `apksigner` with the permanent external key. Allow
`apksigner` to request passwords interactively or obtain them from a protected CI secret store;
never put passwords in a command, committed property file, log, or release artifact.

Create exactly these public artifacts:

- `Poweramp-Remote-Server-v0.10.1.apk`
- `Poweramp-Remote-Phone-v0.4.2.apk`

For each APK, verify:

- APK signature and certificate SHA-256 with `apksigner verify --verbose --print-certs`;
- package ID, `versionName`, and `versionCode` with Android SDK tooling;
- installation and startup on a clean Android device;
- QR pairing, manual pairing, reconnect, playback controls, notification/MediaSession, artwork,
  seek, rating/shuffle, and volume on real devices;
- a published SHA-256 file checksum.

Distribute `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `licenses/Apache-2.0.txt` with the APKs. The
release tag should be created only after the final history decision and may be signed separately
from the APKs.

## Existing debug installations

Previously produced APKs use Android debug certificates, including an older certificate whose
private key is no longer available. A new permanent release key cannot update those installations,
even though the application IDs are retained. Unless an appropriate previous production key is
recovered, the first public release is a fresh-install boundary: testers must uninstall the old
debug build before installing the release APK, which removes that app's local pairing state.

Do not use an Android debug key as the permanent public release key.
