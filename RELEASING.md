# Release checklist

Releases are prepared locally and published manually. Signing configuration and keystores stay
outside the repository. Never publish backup refs or use `git push --mirror`.

## Current candidate: Server 0.11.0 / Phone 0.8.0

- Phone `0.8.0` / code 17 contains the connection-policy and recovery update.
- Server `0.11.0` / code 14 is unchanged. Reuse the exact previously published APK from
  `outputs/release-0.11.0_phone-0.7.0/`; do not rebuild or replace it under the same public version.
- API remains `v1`; permanent application IDs and the release signer are retained.
- Bundle: `outputs/release-0.11.0_phone-0.8.0/` (ignored by Git).
- Suggested tag: `server-v0.11.0_phone-v0.8.0`, at the final Phone APK's embedded source commit.
  The verified Phone embeds `9616976a16a8cdf1daf91f3515807b9c7fc6e8bc`; the reused Server embeds
  its original source commit `6bb22a8`.
- Implementation device matrix: maintainer-confirmed on 2026-11-03. Exact signed-candidate
  installation/smoke checks below remain separate.
- Clean build, 607 JVM test executions, all scheduled lint tasks, signatures, versions, alignment,
  copied licenses, and bundle checksums passed on 2026-11-03. Exact results are in `STATUS.md`.

## Signing and builds

Use the permanent release key and protected backups. Never rotate it for a routine release.
The private properties file contains:

```properties
storeFile=/secure/path/to/poweramp-remote-release.jks
storePassword=<private keystore password>
keyAlias=<private key alias>
keyPassword=<private key password>
```

Pass only its path through `powerampRemoteSigningProperties` or
`POWERAMP_REMOTE_SIGNING_PROPERTIES`; never print its contents or put passwords on a command line.
Missing/incomplete configuration causes release tasks to fail before building.

Commit all application changes before building: Android Gradle Plugin embeds the source revision.
For this Phone-only release, run the required clean debug baseline for both modules and verify
the Phone release variant in the same invocation:

```shell
./gradlew -PpowerampRemoteSigningProperties=/secure/path/signing.properties clean \
  :app:testDebugUnitTest :phone:testDebugUnitTest \
  :app:lintDebug :phone:lintDebug \
  :app:assembleDebug :phone:assembleDebug \
  :phone:testReleaseUnitTest :phone:lintRelease :phone:assembleRelease
```

Gradle signs during packaging. Do not alter a completed APK. Verify signatures, package/version
metadata, embedded source revision, and `zipalign -c -P 16 4` with the installed SDK tools.
Compare the signing certificate to the prior public release. The permanent certificate SHA-256 is:

`C6:09:93:4D:AE:5A:C3:33:CA:9F:58:5C:20:78:76:1D:03:2B:0A:1D:22:E6:A6:03:48:DE:34:F8:D2:83:62:F4`.

## Release folder

Include exactly:

- `Poweramp-Remote-Server-v0.11.0.apk` (byte-for-byte public Server)
- `Poweramp-Remote-Phone-v0.8.0.apk`
- `SHA256SUMS.txt` (the two final APK filenames and hashes)
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `Apache-2.0.txt` (copied from `licenses/Apache-2.0.txt`)

Generate checksums after the final build and run `shasum -a 256 -c SHA256SUMS.txt` inside the
bundle. Keep signing material and local build/debug files out of it. Documentation-only follow-up
commits may record build results and hashes without rebuilding validated APKs.

## Device validation

The full functional connection-policy matrix in `STATUS.md` passed on the maintainer's devices.
Do not transfer that result to an APK built later without checking the exact signed candidate.
Before publication:

- [ ] Install the final signed Phone `0.8.0` over public Phone `0.7.0` without uninstalling.
- [ ] Confirm pairing, Bearer credential, language, Search history, and sorting preferences remain
  intact; verify a normal launch connects to the existing Server `0.11.0`.
- [ ] Check all three transport modes and both manual switches, including LAN confirmation/cancel.
- [ ] With Direct preferred, stop/start Server and verify Direct recovery; check LAN loss/return
  and a short background/screen-off recovery without incorrect transport labels or approval loops.
- [ ] Smoke-test playback, volume, notification/MediaSession, Library/Search/Queue, and log viewer.

Only the maintainer's confirmation of these exact files completes the gate. Fix failures before
publication and record results in `STATUS.md`. Broader vendor coverage remains ongoing.

## Merge and publication

1. Commit release metadata/docs and the subsequent verification record on `codex/connection-policy`.
2. Fast-forward local `main` from that branch, preserving the APK-source commit. If history diverged,
   inspect it before choosing a different merge strategy.
3. The maintainer pushes `main` and creates/pushes `server-v0.11.0_phone-v0.8.0` at the Phone APK
   source revision recorded in `STATUS.md`. The current task does not push or publish anything.
4. Create the GitHub Release from that tag, copy its body from `RELEASE_NOTES.md`, and upload the
   six files from the bundle after the signed-candidate gate is complete.
5. Verify uploaded filenames/checksums and publish. Update release-state wording in `STATUS.md`
   and `ROADMAP.md` only once publication has actually happened.

## Historical compatibility

Server `0.11.0` / Phone `0.7.0` passed the full signed in-place matrix on 2026-09-12 over Server
`0.10.2` / Phone `0.6.0`, on Hiby R4 (Android 12) and Samsung Galaxy S24 Ultra (Android 16).
Its APK-source commit is `6bb22a87c77a7563e4855b460c1507b00def1ecc`; the public tag remains unchanged.

Android debug certificates cannot update a permanent-release-signed installation or vice versa.
Switching certificate families requires uninstalling that app, which removes its private data.
The first public release therefore required fresh installs and re-pairing; subsequent public
releases use the same permanent certificate and must prove in-place compatibility. Use separate
devices/profiles or restore the public release before testing an in-place upgrade after debug use.
