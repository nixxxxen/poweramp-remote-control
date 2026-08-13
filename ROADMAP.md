# Poweramp Remote Roadmap

This document outlines the project's future stages following the release of version `0.7.0`. The current confirmed status of implementation and verification can be found in `STATUS.md`.

## 0.7.x — Native Android Client

- Development of a standalone Android client for phones;
- NSD/mDNS server discovery on the local network;
- Pairing and secure local credential storage;
- Automatic connection to a known R4 and robust reconnection logic;
- Fixes based on testing with actual phones and the HiBy R4.

## Direct Connection Without Manual Hotspot

Investigate Wi-Fi Direct and Local Only Hotspot to enable direct local connections between devices without a pre-configured shared Wi-Fi network or manual hotspot activation. This feature is intentionally omitted from `0.7.0`; the server and phone must still reside on the same IP network.

## MediaSession in Android Client

Implement MediaSession, media notifications, and lock-screen controls in the phone client, including playback control via smartwatches.

## Volume Control

Investigate the volume control method supported by Poweramp/Android and implement it across the server API, Web UI, and Android client, avoiding unverified "magic constants."

## Library and Queue

Add support for browsing and searching the Poweramp library, playing selected tracks, viewing the queue, and performing queue operations using documented or verified integration mechanisms.

## Lyrics

Add support for LRC and embedded lyrics, followed by synchronized lyrics display. Until a verified lyrics source is available, do not rely solely on the `lyricsState` from the public Intent API.

## Further Refinement

Continue improving the UI, pairing security, network change handling, diagnostics, and overall connection stability, while maintaining compatibility with the REST/WebSocket API and the existing Web UI.