---
title: Shortcuts — pin anything; a pin plays at once
kind: feature
status: shipped
area: library
updated: 2026-11-07
---

# Shortcuts

Long-press anything and choose **📌 Pin** or **🏠 Add to home screen**. Pins appear in a **📌 Pinned**
row at the top of the Music tab's home and of Library, and as icons on the phone's home screen. A tap plays
the pin straight away; a video opens the full player. Every pinned and recent tile says what it is under its
title (📻 Radio, 💿 Album, 🎵 Every song, 🔀 Shuffle all, 🎤 Artist, 🎵 Song …), because one artist's radio, album and
songs all carry the artist's name (report 0.1.824: *"isn't the lists a bit ambiguous what is what?"*). Decisions: [ADR 28](../adr/0028-pins-and-home-screen-shortcuts.md).
Un-pinning and "Remove from Recently" offer Undo on the shell's snackbar and put the tile back in its slot
(2026-11-06, [ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md)).

| What | Where |
|---|---|
| The pin model and its codec | `app/…/pins/Pin.kt` |
| Storage, backup | `pins/PinStore.kt`, `pins/PinBackup.kt` |
| Playing a pin | `pins/PinPlayer.kt` (the tile menu's Shuffle and song-seed go through `PinTileActions`, which also records the pin reason) |
| Home-screen and app-icon shortcuts (one dynamic Recently shortcut; the static ones, see [the recorder doc](totum-recorder.md), keep the other launcher slots) | `pins/HomeScreenShortcuts.kt`, `xml/shortcuts.xml`, `MainActivity.handlePinIntent` |
| Menu entries, the row, the page toggles | `ui/common/Pins.kt`, `ui/common/PinnedRow.kt`, `MediaItemSheet.kt` |

## Tests

| Level | Where | What |
|---|---|---|
| unit | `app/…/pins/PinsTest` | every kind round-trips; one pin per key; album as a music group; a video flags the player; an artist with no id plays its mix; a show plays its newest unplayed episode |
| unit | `app/…/ui/common/PinKindTest` | a radio, an album and every song by one artist each say what they are; a shuffled album says so |
| unit | `app/…/pins/PinBackupTest` | pins travel in a backup and merge on restore |

## Tests owed (2026-11-06 consistency sweep)

`PinStore.unpin` / `RecentActions.forget` returning the slot and `restore` putting the tile back there (and clamped when
the list has shrunk); the pinned tile menu's Shuffle and song-seed recording `Chosen(PIN)`; un-pin and Remove from Recently
showing Undo (instrumented).
