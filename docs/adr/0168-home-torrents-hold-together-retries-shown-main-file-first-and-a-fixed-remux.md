---
title: Home torrents hold together: retries are shown, the phone's address is kept, a film is its main file, a late-Tracks MKV is remuxed
kind: adr
status: accepted
updated: 2026-11-14
---

# 168. Home torrents hold together: retries are shown, the phone's address is kept, a film is its main file, a late-Tracks MKV is remuxed

- Status: Accepted
- Date: 2026-11-13
- Amends: [ADR 103](0103-a-torrent-holds-its-place-and-downloads-retry-themselves.md) (the Pi's torrent
  reads), [ADR 105](0105-audiobooks-are-books-from-the-archive-and-torrents.md) (a paused AudioBook Bay),
  [ADR 106](0106-a-torrent-wears-its-poster.md) (a title lookup was "not done"),
  [ADR 110](0110-downloads-at-home-the-pi-fetches-with-the-apps-own-code.md) and
  [ADR 126](0126-a-move-to-the-pi-sends-the-file-and-every-download-says-where.md) (job states, the stored address,
  Move to Phone), [ADR 111](0111-finished-torrents-at-home-are-filed-into-jellyfin.md) (a fresh folder, a remux)

## Context

Diagnostics from the first real uses of home torrents (reports of 2026-11-07 and 2026-11-09, the Pi's logs and
`downloads.json` of 2026-11-13) showed a run of faults with one theme: the film was fine on the Pi and the app said
otherwise, or said nothing.

- A job showed "failed" at 13:58 while the Pi was retrying it; the Spider-Man film landed and was filed at 14:24. The Pi
  reported any failed download as FAILED, the strip ended on it, and polling fell back to every 10 minutes.
- Three retries of one download died at the same byte: the Pi's HTTP client had a 60 s read timeout, and TorrServer
  legitimately holds a request open while it waits for pieces.
- The Pi stored the rewritten address (`http://gluetun:8090/...`) in the job, so a phone that played the job's item
  tried to reach a host that exists only on the Pi's compose network.
- Spinal Tap II came as 12 files: the film (5.5 GB) and two featurettes (108 and 139 MB) among them. The picker
  listed the three alike, in torrent order, the film titled by its file name; the featurettes finished first, so the
  search row said "At home" while the film was at 8%, and the filer left both extras where they were.
- 6 of 13 jobs on the Pi had no picture (the share-sheet route passed none and nothing looked one up).
- The film's MKV has its Tracks element after the first cluster (found through the SeekHead at the end of 5.5 GB), which
  Media3 cannot open.
- Filing left a film in place because its target folder was owned by uid 911, and said so only in a log line.
- AudioBook Bay answered HTTP 400 to every search.

## Decision

- **Retries are part of a job.** `HomeJob.retry` (`HomeRetry`: attempt, of, planned time) is filled from the download
  manager's retry plans. A failure with a plan is reported as WAITING with its reason (so a phone from before this
  change keeps the strip open too); the phone treats a failed job with a plan the same way. The strip and the row say
  "Retrying 2 of 4 in 1 minute: reason" (`Operation.retry`), polling stays at the busy pace, and "Try again now" is offered.
  Only a job out of retries ends the strip as failed and counts as failed.
- **A torrent read waits 10 minutes, and a failure resets the torrent.** Torrent fetches use a client with a 600 s read
  timeout (`torrentReadTimeoutS`); any other fetch keeps 60 s. A transient failure of a torrent job makes TorrServer
  drop and re-add the torrent (`POST /torrents` `drop` then `add` with the info hash, derived from the stream address)
  before the retry. Not run against the live TorrServer: the drop is a change on the Pi, so it is proven against a
  fake only.
- **The record keeps the phone's own item.** The rewrite is applied only inside the strategy (`fetchedAt`), so `jobs()`
  returns the address the phone sent.
- **A file is MAIN, EXTRA or SAMPLE** (`TorrentRoles`): a folder named Extras, Featurettes, Bonus, Behind the Scenes,
  Deleted Scenes, Trailers, Interviews, Making of or Special Features makes a file an extra, as does a video under 15% of
  the torrent's largest video; a Sample folder makes a sample (never queued); the largest file is main when nothing else
  is. Episodic packs (any SxxEyy) and audio are all main. The main file queues first and, when it is the only one,
  takes the torrent's clean name (`Title (Year)`, from the release name); extras follow. The episode picker lists the
  main files with their sizes, and the extras under a collapsed, unticked "Extras" group. Sharing a magnet to
  "Send home" sends the main files only. A search row says "At home" only when its main file is done
  (`SeenFile.extra`, kept in `torrents-seen.json`, is how the row knows). A 🏠 row of a torrent plays through its
  torrent's queue group.
- **A torrent's picture is looked up beside the start, never in front of it** (amended 2026-11-14). `queueItems`
  queues with the caller's picture or none, and a start no longer waits on Cinemeta (one request per word dropped from
  the title, up to the 20 s timeout, before any item existed). The title-and-year lookup (`TorrentPlayables.posterFor`,
  `HomeTorrentServer.posterFor`; Cinemeta's catalogue lookup moved out of `Torrentio` into `Cinemeta` and is shared),
  shortened word by word for a pack, runs alongside: queued items and download records are patched by
  `SharedMetadataRepair`, and a send to the Pi (`SendHome`, the picker's "At home") waits at most 3 s for it
  (`withPoster`) because a Pi job keeps the picture it was started with. An audiobook is never looked up as a film, in
  the lookup or the repair. The decision is logged on every path. A torrent item with no picture is
  repaired like a placeholder title (`SharedMetadataRepair`, queue and downloads), and the Pi repairs its own stored
  jobs at start and when a job without one starts.
- **Filing remuxes a late-Tracks MKV** (`MkvLayout` reads the EBML elements, `MkvRemux` runs `ffmpeg -map 0 -c copy`
  into a temp file beside it, checks the result reads, and renames it over the original); a failure keeps the
  original and is a home problem. A half-written temp file is removed at start.
- **Filing never stops at a folder it cannot write to.** When the chosen folder exists and is not writable (another
  uid), the file goes into a fresh folder named by the rules (`Title (Year)`, numbered when taken); if none can be made it
  stays, and the reason is a home problem on the health banner (`FilingProblems`, kept 24 hours, cleared when the
  file is filed).
- **A paused indexer is quiet.** Prowlarr answers HTTP 400 "all selected indexers being unavailable" while it holds an
  indexer in back-off after errors (checked on the Pi 2026-11-13: AudioBook Bay answers when it is not paused,
  2 to 10 s for five pages). The phone leaves that indexer out, logs one line, and does not ask it again for ten
  minutes. The indexer's own flakiness is not ours to fix; nothing in the definition was wrong.
- **Move to Phone** is one tap on a finished file at home (row button and sheet): the phone copies it (`copyNow`'s
  path), and `PhoneMoves` deletes the Pi's copy (the same `cancel` a phone delete uses, `DeletesAtHomeToo`) only when
  the phone's copy is `Downloaded`, of the same kind (sound or video) and the same size as the Pi's file. A failed or
  mismatched copy leaves the Pi's file; a move left from an earlier run is dropped, not carried out. The set is
  persisted (`totum_moving_to_phone`). Amended 2026-11-14: the persisted set is not settled until the Pi's jobs have
  been read once (the first settle ran against an empty job list and dropped every move as "home no longer has the
  file"), and neither Move to Phone nor Copy now checks the phone's free space first: a file that needs more than the
  phone has is refused with "⚠️ Not enough space on the phone (needs X, has Y)" before anything is fetched or
  recorded as moving; a phone that cannot say its free space copies as before, and the log says so.
- **A sample is a small file with the word in its name** (amended 2026-11-14). `TorrentFile.isSample`: "sample" as a
  whole word and under 250 MB. It replaced "the name contains sample", which dropped a film titled "The Sample…" from
  every listing. The role classifier has only the Sample folder rule; the brief that asked for this named a size and
  word-boundary rule there that does not exist, so the rule lives in `isPlayable`, where the name rule was.
- **The last events survive a restart.** `EventTail` writes the newest 300 events every 10 seconds when they changed;
  at the next start they are restored into the report buffer marked "[before restart]" (`Breadcrumbs.restore`).
  What happened in the last ten seconds before a kill is still lost.

## Consequences

- Deploy order: any. An old phone sees WAITING for a retried job and a stored address it could not resolve; an old Pi
  gives a new phone no retry fields and the old FAILED. The Pi needs a new `totum serve` release for the fixes at home;
  nothing in `server/` changed.
- A Move to Phone deletes a filed film from Jellyfin too (as every delete at home does, ADR 111).
- Extras are still queued after the film when a torrent is played, and a pack of films of similar size has no extras.
  A short video in a documentary pack with unparsed names can be taken for an extra; ticking it in the picker is one tap.
- A single-file torrent is still titled by its release name, a film in a pack by its clean name; not unified here.
- Lengths are not shown in the picker for torrent files: the torrent lists sizes, not durations.
- Tests: `HomeTorrentJobsTest` (cli; address, retry plan, torrent timeout against a stalled local server, reset),
  `HomeRetryingJobsTest`, `TorrentRolesTest`, `TorrentPlayablesTest` (the Spinal Tap listing), `CinemetaTest`,
  `ProwlarrSearchTest` (paused indexer), `PhoneMovesTest`, `TorrentAtHomeTest`, `SendHomeTest`, `LibraryFilerTest`,
  `MkvRemuxTest` (a byte-built fixture and, when ffmpeg is installed, a real remux), `PosterRepairTest`,
  `SharedMetadataRepairTest`, `EventTailTest`, `DiagTest`, and on the device `EpisodePickerTest`.
