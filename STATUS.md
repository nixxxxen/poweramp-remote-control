# Current project status

Current versions:

- Server: `0.8.1` (`versionCode 9`)
- Phone Client: `0.2.1` (`versionCode 9`)
- API: `v1` (unchanged)

## Stage

The repository builds two native Android applications. `:app` is the Poweramp Remote Server for
an Android player device with Poweramp. `:phone` is the native Phone Client. Ordinary LAN/NSD
remains preferred; an already paired Server can be reached through automatic Wi-Fi Direct fallback
when it is unavailable on the shared LAN.

## Implemented in Server 0.8.1 / Phone Client 0.2.1

- Updated the independently versioned applications to Server `9 / 0.8.1` and Phone Client
  `9 / 0.2.1`; API v1 routes, payloads, authentication, and status semantics are unchanged.
- Made Server Wi-Fi Direct publication actively discoverable without opening Android Settings.
  After publishing the existing DNS-SD record, `RemoteWifiDirectPublisher` starts and periodically
  refreshes `discoverPeers()` and observes P2P state, discovery, peers, connection, and channel loss.
- Added channel reinitialization and retry after framework failures. Publication and peer discovery
  resume after Wi-Fi/P2P restoration or group loss and pause while a P2P group is formed.
- Changed the Phone discovery cycle to explicitly clear old requests, run `discoverPeers()`, add the
  DNS-SD service request, and run `discoverServices()`. Stopped discovery, action timeout, `BUSY`,
  and channel loss now use bounded recovery rather than permanently halting fallback.
- Kept service matching restricted to the previously authenticated stable Server identity. No
  Bearer token or resolved address is advertised or used as pairing identity.
- Added service-lifetime Phone receivers for P2P state, discovery, peer, connection, and local-device
  changes. Framework broadcasts no longer depend on the Activity being visible, and stale callbacks
  from an earlier discovery generation cannot cancel or corrupt a newer attempt.
- Added `PhoneConnectionService`, one started-and-bound `connectedDevice` foreground service with an
  ongoing low-importance notification, explicit Stop action, idempotent start, and `START_STICKY`.
  It owns `RemoteClientController`, LAN NSD, the P2P channel/group, network callbacks, REST/artwork,
  the API WebSocket, and both reconnect paths.
- `MainActivity` now binds only while visible. Its `onPause()`, `onStop()`, and destruction do not
  call `cancelConnect()`, `removeGroup()`, stop discovery, close the P2P channel, or close WebSocket.
- A confirmed direct group loss automatically returns to LAN-first discovery and retries direct
  connection. Initial approval/connect failure and unfavorable phone group-owner selection remain
  explicit user-retry states so mandatory Android confirmation dialogs are not looped.
- Added diagnostic logs under `RemoteWifiDirect`, `PhoneWifiDirect`, `RemoteClientController`, and
  `PhoneConnectionService` for publication, discovery, peer count, channel, group, transport, and
  retry transitions without logging tokens or persisting transient addresses.
- Added a pure Wi-Fi Direct recovery policy and unit tests for bounded discovery backoff and the
  distinction between initial approval failure and reconnect after a confirmed group.
- No keep-screen-on flag, wakelock, or Wi-Fi lock was added.

## Preserved behavior

- One existing started-and-bound Server foreground service and one Poweramp integration path.
- LAN-first `_poweramp-remote._tcp.` discovery/publication and recovery after network changes.
- Port `8765`, all API v1 routes, JSON fields, command bodies, HTTP status semantics, Bearer auth,
  browser session auth, CSP/origin checks, and WebSocket client limits.
- Embedded Web UI metadata, artwork, transport, seek, rating/Like/Dislike, and shuffle controls.
- Event-driven complete state snapshots without playback polling or optimistic authoritative state.
- Raw Poweramp `bitRate` and `positionInList` values remain unchanged in API v1.

## Verification

Development verification completed on 2026-08-13 with the pinned Gradle wrapper 8.14.3, JDK 17,
and Android SDK 36.

- Final clean pipeline succeeded:
  `clean :app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug
  :app:assembleDebug :phone:assembleDebug --no-build-cache --no-daemon`.
- Gradle executed all 100 tasks; both debug APKs assembled successfully.
- All `84/84` JVM tests passed across 21 suites with zero failures, errors, or skips:
  Server `62/62` in 14 suites and Phone Client `22/22` in 7 suites.
- Phone lint reports zero issues. Server lint has no source/manifest errors and one maintenance-only
  `AndroidGradlePluginVersion` warning because 8.14.5 is available while the repository intentionally
  uses its pinned 8.14.3 wrapper.
- APK badging confirms Server `versionCode=9`, `versionName=0.8.1` and Phone Client
  `versionCode=9`, `versionName=0.2.1`; both use min API 26 and target API 36.
- Merged manifests confirm both foreground services use `connectedDevice`, are not exported, have
  `stopWithTask=false`, and include the connected-device foreground-service and Wi-Fi prerequisites.
- APK Signature Scheme v2 verification passed for both debug APKs. Both have one Android Debug
  signer with certificate SHA-256
  `7112c13a7d98be65d011603fc01855f594b60b7bdd748d814bb3a9630e4f9b42`.
- Delivery artifacts:
  - `outputs/Poweramp-Remote-Server-v0.8.1-debug.apk` — 118,530 bytes, SHA-256
    `efdfe4cddba99cecf257e0cbb50eb23f344912ffe1d0fa6c31d8e16856c3a101`.
  - `outputs/Poweramp-Remote-Phone-v0.2.1-debug.apk` — 101,147 bytes, SHA-256
    `2647abbaf6b0d7893d1716412aacba6be86db53a3082f1dfbdf3d399c6c6985a`.

## Real-device checks required

No Wi-Fi Direct hardware pass is claimed by JVM, lint, manifest, or APK verification. Before release
validation, use the target HiBy R4 and phone to confirm:

- With no shared LAN, both applications discover and connect without opening the system Wi-Fi
  Direct screen on the player device.
- After a direct connection is usable, Home/background and phone screen lock leave the P2P group,
  API WebSocket, state updates, artwork, and controls working.
- A temporary P2P disconnect triggers automatic LAN-first recovery and direct reconnect without
  requiring an Activity restart; any mandatory Android approval is still completed manually.
- Normal LAN NSD remains preferred, reconnects after DHCP/Wi-Fi changes, and replaces a direct route
  when an ordinary LAN endpoint recovers.
- Nearby devices permission on Android 13+, legacy location permission/Location Mode on Android
  8–12L, Wi-Fi off/on, rejection, timeout, channel loss, and phone group-owner selection all produce
  the expected recoverable state and diagnostic log sequence.
- Bearer REST, browser session auth, API v1 WebSocket, embedded Web UI, and all player controls pass
  regression testing over LAN and the direct group-owner address.
- Upgrade from the previously installed APKs preserves the Server token and Phone pairing.

## Known limitations

- HTTP and `ws://` remain plaintext at the application layer. Do not expose port `8765` to the
  internet.
- Initial token pairing still requires a shared LAN; direct fallback deliberately accepts only an
  already verified Server identity.
- The current direct IPv4 route requires the Poweramp device to become P2P group owner. Android may
  choose otherwise despite the client preference; the UI then asks the user to retry or use LAN.
- Wi-Fi Direct and system approval behavior remain vendor-dependent and require the hardware matrix
  above; a foreground service improves process lifecycle but does not bypass OEM radio policy.
- Force-stop, explicit notification Stop, or reboot ends the corresponding runtime until the app is
  launched again. No lock is held to override platform power management.
- Exact public semantics of Poweramp bitrate units and list index base remain unverified.
- MediaSession/lock-screen media controls remain roadmap work; lyrics remain intentionally out of
  scope.

## Next scope

Complete the real-device connection matrix and any evidence-based vendor-specific hardening for
Server `0.8.x` / Phone Client `0.2.x`, then continue the priorities in [`ROADMAP.md`](ROADMAP.md).
