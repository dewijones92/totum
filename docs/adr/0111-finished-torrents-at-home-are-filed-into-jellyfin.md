---
title: Finished torrents at home are filed into Jellyfin by Haiku
kind: adr
status: accepted
updated: 2026-10-31
---

# 111. Finished torrents at home are filed into Jellyfin by Haiku

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi: *"it downloads on the Raspberry Pi in a temporary location … when it's finished, move it into like the
Jellyfin series or movies and pick the appropriate subfolder … do some dry runs with what's already there … parent
folder, Slow Horses, subfolder series one, existing episodes … for the new file to go to the correct node … make
sure … we can still play it from our app … the move needs to somehow trigger the Jellyfin update library thing."*
He chose "Haiku decides every time" over rules.

The library (1,611 videos on 2026-10-31) is named every way a release can be: `Slow Horses/Series 1`,
`…Poirot S01-S13 720p…/s10`, `Austin 2024 S02 720p WEB-DL…`, loose `Elemental.2023….mkv` files, films in their
release folders. Jellyfin has online metadata off, so the names are all it has.

## Decision

- **When a torrent job at home finishes, `totum serve` files it** (`LibraryFiler`, `cli/…/serve/filing/`): it shows
  Haiku the file's name, the torrent's name and the closest folders in both libraries (their season folders and
  files, three levels deep) and every other top-level name, and Haiku answers one structured plan: episode, film or
  leave; the library; the folder (existing names reused exactly, the season folder that already holds that season
  whatever it is called); the file name.
- **The plan is checked before anything moves** (`Filing.valid`): a relative path inside the library, a plain file
  name keeping its extension, and the same SxxEyy as the file. A refused plan, a failure or a Pi not signed in to
  Claude files **by rules** instead (`Filing.byRules`: the best-matching show and its season folder, or
  "Title (Year)/Title (Year).ext"). A folder name copied with a changed separator snaps to the folder that exists
  (`Filing.snapped`).
- **A rename, not a copy**: the container mounts the whole media disk as `/media`, so the download folder and the
  library are one filesystem. It runs as uid 1000 so what it creates is pi's. A name already taken gets " (2)"; a
  folder it cannot write to leaves the file where it is, logged with the reason.
- **The app keeps finding it**: the download record's path is moved with the file (`JsonDownloadStore.relocate`),
  so `/files`, the 🏠 rows, copying to the phone and deleting all follow it. At start a record whose file is no
  longer where it says but is in the download folder is pointed at it (the mount change moved every path).
- **Jellyfin is told**: `POST /Library/Media/Updated` with the file's path as Jellyfin sees it (`/data/movies`,
  `/data/tvshows`), falling back to a full `/Library/Refresh`; the key is an API key named "Totum" in `fetch.env`.
- Only torrents, only video files, and never a sound-only copy; YouTube and podcast downloads stay in `totum/`.

## Consequences

- Dry runs against the real library (`totum file-check <listing> --samples=N --seed=S [--rules]`: each sampled video
  is hidden, a film's whole folder with it, and the plan scored against where it really lives):

  | Run | Haiku: library / show or film / exact folder | Rules: exact folder |
  |---|---|---|
  | 40 videos, seed 1 | 40 / 40 / 40 | 29 |
  | 120 videos, seed 7 | 120 / 120 / 116 | 94 |
  | 80 videos, seed 11 | 80 / 80 / 80 | 67 |

  About 5 s a file. The first seed-7 run (117/120) found three faults that are now guarded: a folder name copied
  with `-` turned into `.` (snapped), a show missed because `01e06 Belief.avi` looked like a film (the context now
  shows both libraries), and S01E03 written as S03E03 (a different episode is refused). Of the four seed-7 misses
  left, two wrote the show's folder twice (`Succession/Succession/…`, now taken once) and two are the scoring's own
  limits: an "Extra" filed as an episode, and an episode put in its season folder rather than the per-episode release
  folder a fresh download would not have.
- 🏠 Delete at home now deletes a filed film from the Jellyfin library too, which is what "delete" said all along.
- A Haiku call per finished torrent (about 5 s, on the Pi's Claude account).
- The container can now write anywhere on the media disk; the filer refuses any target outside the two library
  roots.
