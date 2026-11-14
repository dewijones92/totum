---
title: 🏠 Downloads at home (the Pi fetches; the phone copies when you ask)
kind: feature
status: built — proven end to end 2026-10-31; the 2026-11-13 retry, Move to Phone and filing fixes (ADR 168) are unit-tested but not run against the live TorrServer; playing from the Pi's copy before the phone has its own is not yet proven live
area: downloads
updated: 2026-11-14
---

# 🏠 Downloads at home

When the phone's signal is poor, choose 🏠 At home in the ⬇️ question: the home server downloads it with the app's
own code (torrents, podcasts, YouTube with your sign-in), and you can play it from home straight away. Playing or
queueing a file that is at home never copies it to the phone; the phone copies only when you choose 📥 Copy to the
phone now, or when you turn on the setting below. ADR 110.

## What you see

- ⬇️ asks where (📱 On this phone / 🏠 On the Pi); on mobile data 🏠 is suggested; "remember" keeps it.
- **Every download says what moves and where**, in one wording on every surface (rows, the Library card, the strip,
  notifications): `⬇️ Web → 📱 Phone · 45%`, `⬇️ Web → 🏠 Pi · 45%`, `📥 🏠 Pi → 📱 Phone · 45%`,
  `📤 📱 Phone → 🏠 Pi · 45%`; waiting is `⏳ … · waiting`, failed is `❌ … · failed: why`. When it rests:
  `✅ On 🏠 Pi · stays there until you copy it to 📱 Phone` (or `… copies to 📱 Phone on Wi-Fi`) and
  `✅ On 📱 Phone and 🏠 Pi`. One pure `Transfer` in `:core:domain` and one mapper (`transferText` in `ui/common`).
- The progress strip and a notification while it runs, in the same wording (`⬇️ Web → 🏠 Pi · title`); "✅ On 🏠 Pi · title" when done.
- Library → Downloads → 🏠 On the Pi opens with the Pi's space, like the phone's: "🏠 Using 1.8 GB on the Pi ·
  139 GB free" and a meter (Totum's finished downloads, and the disk's free space, from every job-list read).
- Library → Downloads → 🏠 On the Pi: every job as an ordinary item row (poster, ⋮ menu, tap to play from the Pi), with
  📥 Copy to 📱 Phone now, Retry, Cancel and 🗑️ Delete from Pi.
- Every delete says where from. Any item's menu, once it is on the Pi: 📥 Copy to 📱 Phone now (any network),
  🗑️ Delete from both, 🗑️ Delete from phone (keep on Pi) (it then stays there until copied again by hand),
  🗑️ Delete from Pi (keep on phone); with no Pi copy the one row is 🗑️ Delete from phone. A bulk delete asks first and
  is labelled 🗑️ Delete from both when any selected item has a Pi copy (the row's tick says "delete from both" too).
- 🏠 Send home in any app's share sheet: a YouTube link or a magnet goes straight to the Pi, without opening Totum; a
  toast says "⬇️ Web → 🏠 Pi · …" or "❌ Not sent home: …" (`SendHome`, `SendHomeActivity`).
- A download on the phone offers 📤 Copy to 🏠 Pi and 📤 Move to 🏠 Pi in its menu. **The phone's file is really sent**
  (`PUT /files?id=`, ADR 126): a `📤 📱 Phone → 🏠 Pi · 45%` strip row and notification, Cancel stops it, and a move
  deletes the phone copy once the Pi's job list shows the file. Only a phone copy with no file on disk falls back to the
  Pi fetching it from the web. The Pi refuses a file above its limit (free space less 20 GB, at most 64 GB) and says why. A video that finishes at home (torrent or YouTube) is filed into Jellyfin when Haiku sees a whole film
  or episode (ADR 111).
- A queue group's ⋮ → 🏠 Keep the next 2 (or 5) on the Pi: the next unplayed members go home and are topped up as you
  listen; on the 📥 New group or a shuffled channel this fetches new uploads at home as they arrive (`HomeAhead`).
- Settings → Downloads → 🏠 Copy Pi downloads to the phone (off by default: everything stays home until asked; on copies it on Wi-Fi by itself). The queue's automatic
  download skips anything already at home.
- A finished torrent (film or episode) is filed into Jellyfin's movies or series library by Haiku, into the show's
  existing season folder, and Jellyfin is told; the app still plays, copies and deletes it there (ADR 111).
- Deleting the download from the phone's usual delete deletes both copies. Under 20 GB free at home a job is refused
  with the reason.

## Seams

- Phone: `downloads/home/` (`HomeDownloads`, `SendHome`, `HomeAhead`, `HomeFollower`, `HomeNotifications`, `DeletesAtHomeToo`),
  `DownloadChooser`/`EpisodeDownloads` (where), `PlaceChips`, `LocalHomeJobs`, `HomeSection`.
- Shared: `HomeJobsClient`/`HomeJobsApi` and the wire types in `core/data/.../home/`, `HomeCopyFirst`,
  `routeNow(atHome = …)`, `PhoneCodecs`/`VideoCodecFamily`.
- Home: `totum serve` (`HomeDownloads`, `HomeRoutes` in the CLI, `receive` for uploads), `server/totum-fetch`.
- One authed call shell for every Pi call: `HomePiCall`; the upload body is `HomeUploadBody`/`HomeUploadFrame`; the phone side
  is `PhoneUploads`.

## Status

*Status correction (2026-11-14): the frontmatter said `refining` although this section had described a built, proven feature since
2026-10-31. It is built; tests exist on both sides (`HomeDownloadsTest`, `HomeCopyFirstTest` in the app and `:core:data`, and
`HomeTorrentJobsTest`, `HomeFilingTest`, `HomeRoutesTest` on the Pi in `:cli`). What is not confirmed on a device or the real Pi:
the 2026-11-13 work below (ADR 168 says the TorrServer drop-and-re-add is "not run against the live TorrServer") and playing
from the Pi's copy before the phone has its own.*

Built and unit-tested on both sides, and proven end to end on 2026-10-31 (v0.1.807 on the Pi, the emulator as the
phone): a podcast episode sent home (8.8 MB in 2 s), announced "🏠 Ready at home" and copied over Wi-Fi through
`HomeCopyFirst`; a YouTube video sent home as 480p handed the sign-in over, was refused by yt-dlp and fetched by the
signed-in route on the Pi (147 MB, n solved by Node), then replaced the phone's sound-only copy; deleting either on
the phone deleted the copy at home. Not yet proven live: playing from the Pi's copy before the phone has its own
(unit-tested; the same URL and token served the copy).

First real use (report 20261031T193811-51dfc9b5, v0.1.811): Toy Story 5 (1080p 10-bit x265, 1.96 GB) sent home from
the phone, done on the Pi in 7 minutes, copied to the phone over Wi-Fi in ~70 s. The phone's copy showed as a plain
"⬇️ Downloading 70%", which read as the Pi lagging; it now says "📥 Copying from home".

## Retries, Move to Phone and the event tail (2026-11-13, [ADR 168](../adr/0168-home-torrents-hold-together-retries-shown-main-file-first-and-a-fixed-remux.md))

- **A job the Pi is retrying is not "failed".** `HomeJob.retry` carries attempt, of and the planned time; the strip and the
  row say "Retrying 2 of 4 in 1 minute: reason", polling stays at the busy pace, "Try again now" is offered, and only a job
  out of retries ends as failed. Torrent reads wait 10 minutes (TorrServer holds a request open for pieces) and a failed
  torrent job makes TorrServer drop and re-add the torrent before the retry.
- **The Pi keeps the phone's own address** in a job's item; the rewrite to `gluetun:8090` happens only when fetching.
- **📥 Move to 📱 Phone** (row button and sheet, any finished file at home): copy first, then the copy at home is deleted
  only once the phone's copy has landed, is the same kind and the same size (`PhoneMoves`, `totum_moving_to_phone`).
- **Filing**: a late-Tracks MKV is remuxed with `ffmpeg -c copy`; an unwritable target folder is skipped for a fresh
  clean-named one; either failure is a home problem on the health banner.
- **Pi posters**: stored jobs with no picture get one by title and year at start and when a job starts.
- **The last 300 events** are written every 10 seconds and restored at the next start marked "[before restart]".
