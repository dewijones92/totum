---
title: The floor is Android 13 (minSdk 33), so Totum runs on a Pixel 4a
kind: adr
status: accepted
updated: 2026-10-14
---

# 39. The floor is Android 13 (minSdk 33), so Totum runs on a Pixel 4a

- Status: Accepted; supersedes the July 2026 "minSdk 34" row in CLAUDE.md (no ADR recorded it)
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"make the app work on pixel 4a"*. His 4a is on stock Android 13 (API 33), its last
official update; Totum required 14. No library needs 14 (checked from the cached AARs); the floor was a
choice. A read-only audit and Android lint (`NewApi`/`InlinedApi`, error severity) listed what used 14+.

## Decision

- `minSdk = 33` in `gradle/libs.versions.toml`.
- Android-14-only calls are guarded in the reminder kit (`reminders/kit/ForegroundKind.kt`):
  `startForegroundTyped(id, notification, ForegroundKind, tag)` passes the `health` / `systemExempted` type on
  14+ and uses the two-argument `startForeground` on 13 (the manifest's type); `mayUseFullScreenIntent`
  answers true below 14, where the permission is granted at install. The Exsurge page omits the
  full-screen permission row below 14; the alarm tile uses the `Intent` form of `startActivityAndCollapse`.
- `LocalDate.ofInstant` / `LocalTime.ofInstant` (Java 9, Android 14) are replaced by `atZone(...)`, including
  in the pure-JVM modules that lint does not check.
- CI stays on its API-35 emulator, building only (Dewi's choice); local checks on a new `totum-api33` AVD.

## Consequences

- Verified on `totum-api33` (Android 13, Pixel 4a profile): launch, the alarm board pinned as a real foreground
  service, the Exsurge page and a takeover, and a YouTube stream with sound. Not exercised there: the
  step-counter service (the emulator has no step sensor) and a ringing alarm.
- Pure-JVM modules are not linted against Android's API; a Java API newer than 13 used there would only show
  on a device. The `atZone` replacements were found by hand.
