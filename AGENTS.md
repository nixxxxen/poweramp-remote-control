# AGENTS.md

## Purpose

This repository contains a native Android foreground client that integrates with Poweramp.

The current project version is `0.6.0`.

The project is focused on Poweramp integration and includes an authenticated local HTTP/WebSocket server plus a compact same-origin Web UI owned by an Android foreground service. There is no separate phone application.

## Read first

Before any non-trivial change:

1. Read `PROJECT.md`.
2. Read `STATUS.md`.
3. Inspect the existing implementation before adding a new integration path.
4. Preserve all functionality that is already confirmed working in version `0.3.0`.
5. Prefer documented/public Poweramp APIs, intents, broadcasts, constants, and already-used integration mechanisms.
6. Do not assume that an API field, unit, index base, event, or command behaves a certain way unless it is verified by documentation, source/API definitions, tests, or device behavior.

## Current architecture

The actual project is a native Android client with one in-process foreground service and local API.

Version `0.2.0` did not contain a separate Android server or web client, so version `0.3.0` extended the existing native foreground client.

Version `0.4.0` added the HTTP/WebSocket layer. Version `0.5.0` added the embedded Web UI and browser-cookie sessions. Version `0.6.0` moves the existing Poweramp/network runtime into one started-and-bound `connectedDevice` foreground service so it survives Activity backgrounding and screen lock. Do not introduce a phone client, cloud dependency, duplicate service, or unrelated architectural rewrite unless explicitly requested.

## Regression-sensitive baseline: version 0.3.0

The following functionality is implemented and working:

### Track metadata

- title;
- artist;
- album;
- album artwork;
- codec / file type;
- bit depth;
- sample rate;
- bitrate;
- source category;
- position in the current list.

### Playback state and automatic updates

- playback state;
- metadata refresh on `TRACK_CHANGED`;
- metadata/status refresh on `STATUS_CHANGED`;
- shuffle state refresh on `PLAYING_MODE_CHANGED`.

### Transport controls

- play;
- pause;
- previous track;
- next track.

### Rating controls

- rating values `0…5`;
- Like;
- Dislike;
- exact Poweramp `SET_RATING` command is used.

### Shuffle

- binary shuffle OFF/ON control;
- shuffle state tracking through `PLAYING_MODE_CHANGED`.

### Intentionally not implemented

- lyrics.

Lyrics were intentionally left out in version `0.3.0`. Do not add them unless explicitly requested in a future task.

## Known unresolved details

The following values work in the implementation, but their exact public semantics are not fully guaranteed by Poweramp documentation:

- units of `bitRate`;
- index base of `posInList`.

These should be checked on the target R4 device/environment, especially:

- bitrate values for known files;
- first item of a list;
- last item of a list.

Do not silently change conversions or index offsets without evidence.

## Implementation rules

For every new capability:

1. Locate the existing Poweramp integration path first.
2. Reuse the current event/update architecture where possible.
3. Keep Poweramp-specific constants and parsing isolated from presentation code.
4. Handle absent or unsupported optional values gracefully.
5. Preserve automatic refresh behavior.
6. Avoid polling when an existing Poweramp event can provide the update reliably.
7. Avoid magic numbers when official constants already exist.
8. Keep changes focused on the requested feature.

## Verification

Version `0.3.0` baseline passed:

- 12/12 unit tests;
- lint with 0 errors;
- debug APK build;
- APK signing;
- successful rebuild from an unpacked source ZIP.

Version `0.4.0` additionally passed 39/39 JVM tests (including loopback REST/WebSocket coverage), lint with 0 errors, a clean debug APK build, and APK Signature Scheme v2 verification. See `STATUS.md` for the latest `0.6.0` verification results.

For future changes:

- run the existing unit tests;
- run lint;
- build the debug APK;
- verify regressions in existing Poweramp metadata and controls;
- test any newly added event handling or commands;
- update `STATUS.md` with the result.

If repository scripts or build commands already exist, use them instead of inventing a new workflow.

## Project memory

`PROJECT.md` contains durable project scope, architecture, and integration behavior.

`STATUS.md` is the current handoff document and must reflect the latest confirmed implementation state.

After a meaningful completed task, update `STATUS.md` with:

- version, if changed;
- what was added or changed;
- files/components affected;
- tests/build verification;
- device-specific findings;
- known limitations;
- next unresolved work, if any.

Keep `STATUS.md` concise enough for a fresh Codex session to read quickly.

## Decision priority

When information conflicts, use this priority:

1. explicit instructions in the current user task;
2. `AGENTS.md`;
3. `PROJECT.md`;
4. `STATUS.md`;
5. current repository implementation;
6. assumptions.

If documentation and code disagree, inspect the code and correct the documentation when appropriate.
