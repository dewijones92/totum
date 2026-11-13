---
title: Downloads at home — the Pi fetches with the app's own code
kind: adr
status: accepted
updated: 2026-11-06
---

# 110. Downloads at home: the Pi fetches with the app's own code

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi: *"when I'm out and about, I want an easy way to kick off torrent film downloads … on the Raspberry Pi … a thin
layer and doesn't repeat itself … visually obvious in the app … download anything that we can download in the app
currently … my phone sometimes doesn't have good internet."* He chose: everything (YouTube too), a choice in the ⬇️
sheet, play from the Pi and copy to the phone on Wi-Fi, the CLI in a Docker service, the phone handing its YouTube
sign-in over, the Pi keeping files until he deletes them (refusing new ones under 20 GB free), and every way of
showing progress (rows, a 🏠 section, notifications, the strip).

A spike on the Pi (2026-10-31): anonymous yt-dlp is bot-checked from the home IP ("Sign in to confirm you're not a
bot"), while the signed-in TV request answered with 40 fetchable formats and served 1080p60 and audio.

## Decision

- **One service, `totum serve`, in `server/totum-fetch`** (the same container as ✨ Ask, ADR 109), behind the
  device token at `totum.<home>/fetch/`: `POST /jobs`, `GET /jobs`, `DELETE /jobs/{id}`, `POST /jobs/{id}/retry`,
  `GET /files/{id}` (ranges), `PUT`/`DELETE /account`.
- **The app's own code, not a second copy.** YouTube resolution moved from `:app` to `:core:video` (ADR-less move,
  2026-10-31) so the CLI can run it; the routes are built once by `videoAndPodcastDownloads` (yt-dlp first, the
  signed-in path second, podcasts and torrents over HTTP) and called by both composition roots; SABR, the one
  Android-only route, is a `SabrRoute` port the Pi leaves empty. The download manager, its retries and its notes are
  `DefaultDownloadManager`; the Pi stores its list in `JsonDownloadStore` where the phone uses Room.
- **The CLI engine does what the phone's does.** `ProcessYtDlpEngine` now downloads (progress streamed as lines),
  solves `n` and signatures, and merges, through the same Python bridge and the same `FfmpegMerge`; the JS runtime is
  named by `TOTUM_JS_RUNTIME` (Node in the container). Until now the desktop never actually enabled one.
- **The phone decides the format, as far as the phone itself does.** A job carries the item (the backup format),
  Sound or Video, the height ceiling, and the phone's decoders (`PhoneDecoder`: family, highest height, hardware,
  kept on disk with the job). The signed-in route chooses by them (`PhoneCodecs`, one `VideoCodecFamily` table
  shared with the phone's own check); the yt-dlp route takes only the height, exactly as it does on the phone
  (`selectorFor`). From home yt-dlp is bot-checked, so in practice the signed-in route answers.
- **Torrents are read from TorrServer directly** (`TOTUM_REWRITE`, the public `/ts/` address rewritten to
  `gluetun:8090` on the compose network), so the Pi needs no device token for its own server.
- **Space:** files land in `/home/pi/ext_files/media/totum`; a job is refused with "❌ Not enough space at home"
  under 20 GB free; nothing is deleted except by the phone.
- **🏠 Send home from any app** (2026-11-01, Dewi's pick from the home use cases): a second entry in Android's share
  sheet, "🏠 Send home" (`SendHomeActivity`, translucent, gone at once, a toast says what happened). A YouTube link
  goes home with the remembered sound/video choice (best video when none is remembered); a magnet is prepared by the
  home TorrServer and each playable file goes home. One rule, `SendHome`, decides; sharing to Totum itself still
  plays at once.
- **🏠 Also at home / 🏠 Move to home** (2026-11-01, docs/todos/move-to-home.md): any download on the phone that is
  not at home offers both in its menu; home fetches it itself in the phone copy's variant (sound or video). A move
  deletes the phone copy once home reports it ready, through "delete on the phone only", so it is kept at home and
  not copied back; a refused, failed or cancelled job, a later copy request, or a move left from an earlier run keeps
  the phone copy; a 🔒 locked download offers only the copy (`HomeDownloads.sendFromPhone`, a "moving" id set kept
  in prefs).
- **🏠 Keep the next N at home** (2026-11-01): a queue group's ⋮ offers "Keep the next 2 / 5 at home"; the next N
  unplayed members after what is playing (from the group's start when it is not playing) are sent home with the
  remembered choice, and topped up as you move on (`HomeAhead`, `aheadFor`), counted from the queue's place (its
  anchor when nothing plays). Nothing is sent until home's list has been read; an item that was at home and went
  (cancelled there) is not sent again; a send that never arrives is tried again after 30 minutes. Fetch ahead only, nothing is deleted;
  members already at home or on the phone count towards N. The choice is kept per group id, so the 📥 New group and a
  shuffled channel's group keep fetching their new uploads at home as `NewUploads` adds them (the "overnight prefetch"
  idea: its worker runs every 2 h on Wi-Fi, and the phone copies on Wi-Fi as usual).

## Consequences

- Proven on the laptop on 2026-10-31: a YouTube job with the handed-over sign-in fetched 480p video + audio through
  the signed-in route (49 MB in 5 s), a podcast job over HTTP, ranges served (206, 200, 416), the list and sign-in
  kept across a restart, delete removing the file.
- One YouTube sign-in now lives in two places (Dewi's choice): 600 on the Pi, never logged, forgotten when the
  phone signs out.
- yt-dlp in the container updates itself at start, like the app's; the CLI is installed from a release, so the Pi
  runs only released code.

## The phone side (2026-10-31)

- **Where, in the ⬇️ question.** With a home server, the download question gains 📱 On this phone / 🏠 At home
  (`PlaceChips`), shown for every downloadable item, not only those with a sound-or-video choice, until "remember"
  keeps the place (`AppPreferences.downloadPlace`); on mobile data 🏠 is suggested. The episode picker has the same
  row. A selection with a remembered choice but no remembered place opens the picker instead of asking per item.
  This widens ADR 77's "a podcast downloads at once" for the place only.
- **`HomeDownloads`** sends the job (the item, Sound or Video and height, the phone's decoders from
  `PlatformVideoCodecSupport.snapshot()`), hands the YouTube sign-in over once before the first video, polls every
  2 s while anything runs (ADR 126; it was 20 s) (10 min otherwise, and on every network change), and drives the strip
  ("🏠 Downloading at home: …" with a percentage) and notifications (started, ready at home, refused; tapping opens
  Downloads at 🏠 At home). A job already finished when the app started is not announced.
- **Rows say so** through one root-provided state (`LocalHomeJobs`): "🏠 Downloading at home · 45%", "🏠 At home ·
  copies to the phone on Wi-Fi", "❌ Failed at home: …", in every list's status line, until the phone has its own
  copy. Downloads has a 🏠 At home section with Retry, Cancel and Delete.
- **Playing:** `routeNow` takes a finished copy at home (`HomeCopy`) when there is none on the phone and the phone
  is online, and plays the Pi's file as a stream (the token header is the player's usual one).
- **Copying:** by hand (📥 Copy to the phone now, any network), or, when "Copy home downloads to the phone" is on
  (**off by default since 2026-11-06**), on an unmetered network for every finished job with no copy on the phone,
  through the ordinary download manager, whose first route (`HomeCopyFirst`) fetches the Pi's file when it has one
  (resumable); a sound-only copy never stands in for a video. The queue's automatic download skips anything finished
  at home, so playing or queueing a Pi file never copies it (Dewi, 2026-11-05: "if a file is on my Raspberry Pi and I
  play it … I don't want it to be downloaded"). Until 2026-11-06 the setting was on by default and the queue
  auto-download copied Pi files on any network.
- **Deleting** a download on the phone deletes its copy at home too (`DeletesAtHomeToo`), unless the phone kept
  it (locked). Signing out of YouTube asks the Pi to forget the handed-over sign-in.

Amended 2026-11-13 ([ADR 166](0166-home-torrents-hold-together-retries-shown-main-file-first-and-a-fixed-remux.md)): a job keeps the phone's own item address (the rewrite is applied only to fetch), and a failure with a retry planned is reported as waiting with its plan.
