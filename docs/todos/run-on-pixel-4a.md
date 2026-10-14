---
title: Make Totum run on a Pixel 4a
kind: todo
status: top of the backlog (Dewi 2026-10-14) — in progress
area: platform
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Run on a Pixel 4a

Dewi, 2026-10-14: *"make the app work on pixel 4a"*.

## The blocker

- The Pixel 4a's last official update is **Android 13 (API 33)**; Totum's `minSdk` is **34** (Android 14),
  a deliberate decision recorded in CLAUDE.md's Decisions table (July 2026). So the APK will not install.
- The release APK is `arm64-v8a` only; the 4a is arm64, so that part is fine.

## Answered (Dewi, 2026-10-14)

"android 13 i think (i have not modded the pixel 4a) lets just queue the investigation." So the work is
lowering `minSdk` to 33, below. Not started.

## Questions for Dewi (asked)

- Which Android is the 4a on: stock Android 13, or a custom ROM (e.g. LineageOS) that is on 14+? On 14+
  nothing needs changing beyond trying it.
- If stock 13: lower `minSdk` to 33. That reverses a recorded decision (needs an ADR) and means auditing
  every API-34+ call: foreground-service types and `startForeground` rules, `canUseFullScreenIntent`
  (API 34), the Android 16 Live Update banner (already version-gated), partial media-permission
  handling, predictive back, and the exact-alarm permission flow.

## Investigation (2026-10-14, a read-only scout, its key sites re-checked by grep)

Small code change; the effort is the API-33 run, not the edits. No library needs 34 (the cached
javascriptengine, media3, work, mediarouter, room and navigation3 all ask for 26 or less); the floor is
CLAUDE.md's recorded choice, which no ADR records, so lowering it needs a new ADR.

| Site | API | On Android 13 | Fix |
|---|---|---|---|
| `exsurge/AndroidExsurgePorts.kt:52` `canUseFullScreenIntent()` | 34 | unguarded: expected crash when a takeover shows | `if (SDK_INT >= 34) … else true` |
| `ui/settings/ExsurgeSettingsScreen.kt` permissions row (`canUseFullScreenIntent`, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) | 34 | expected crash building the Exsurge page | omit the row below 34 |
| `exsurge/ExsurgeBannerService.kt:48` `FOREGROUND_SERVICE_TYPE_HEALTH` | 34 | unknown; inside `runCatching`, failure stops the step counter | test on 33; two-argument `startForeground` below 34 |
| `dailyalarms/RingService.kt:46`, `AlarmBoardService.kt:30` `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED` | 34 | unknown; failure rings unpinned / posts the board unpinned | same |

- Manifest: the 34-only `FOREGROUND_SERVICE_*` permissions and `health`/`systemExempted` types are
  expected to be ignored on 33; to be checked on the device.
- Live Update (API 36) is already guarded; everything else used is API 31 or older.
- CI emulators are API 35 (`ci.yml:87`, `exsurge.yml:89`); add an API-33 job or a local `totum-api33`.
- Docs that cite minSdk 34 as a reason: CLAUDE.md Decisions row, `docs/features/brand.md:89`,
  `tools/ffmpeg/README.md:6` (W^X is older than 14, so that wording is likely wrong; unverified).
- ADRs 5, 18 and 21 describe Android 14 behaviour (swipeable ongoing notifications, `systemExempted`);
  their 33 behaviour has to be checked.

## Done when

Totum installs and its main flows (playback, downloads, Exsurge, Daily alarms) work on the 4a, checked on
the device or on an API-33 emulator (`totum-api33`), with CI building for the new floor.
