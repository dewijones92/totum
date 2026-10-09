---
title: Shortcuts — pin anything; a pin plays at once
kind: feature
status: shipped
area: library
updated: 2026-10-09
---

# Shortcuts

Long-press anything and choose **📌 Pin** or **🏠 Add to home screen**. Pins appear in a **📌 Pinned**
row at the top of the Music tab's home and of Library, and as icons on the phone's home screen. A tap plays
the pin straight away; a video opens the full player. Decisions: [ADR 28](../adr/0028-pins-and-home-screen-shortcuts.md).

| What | Where |
|---|---|
| The pin model and its codec | `app/…/pins/Pin.kt` |
| Storage, backup | `pins/PinStore.kt`, `pins/PinBackup.kt` |
| Playing a pin | `pins/PinPlayer.kt` |
| Home-screen and app-icon shortcuts | `pins/HomeScreenShortcuts.kt`, `MainActivity.handlePinIntent` |
| Menu entries, the row, the page toggles | `ui/common/Pins.kt`, `ui/common/PinnedRow.kt`, `MediaItemSheet.kt` |

## Tests

| Level | Where | What |
|---|---|---|
| unit | `app/…/pins/PinsTest` | every kind round-trips; one pin per key; album as a music group; a video flags the player; an artist with no id plays its mix; a show plays its newest unplayed episode |
| unit | `app/…/pins/PinBackupTest` | pins travel in a backup and merge on restore |
