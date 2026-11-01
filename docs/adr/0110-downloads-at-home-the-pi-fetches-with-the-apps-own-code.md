---
title: Downloads at home — the Pi fetches with the app's own code
kind: adr
status: accepted
updated: 2026-11-01
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
  20 s while anything runs (10 min otherwise, and on every network change), and drives the strip
  ("🏠 Downloading at home: …" with a percentage) and notifications (started, ready at home, refused; tapping opens
  Downloads at 🏠 At home). A job already finished when the app started is not announced.
- **Rows say so** through one root-provided state (`LocalHomeJobs`): "🏠 Downloading at home · 45%", "🏠 At home ·
  copies to the phone on Wi-Fi", "❌ Failed at home: …", in every list's status line, until the phone has its own
  copy. Downloads has a 🏠 At home section with Retry, Cancel and Delete.
- **Playing:** `routeNow` takes a finished copy at home (`HomeCopy`) when there is none on the phone and the phone
  is online, and plays the Pi's file as a stream (the token header is the player's usual one).
- **Copying:** on an unmetered network every finished job with no copy on the phone is downloaded through the
  ordinary download manager, whose first route (`HomeCopyFirst`) fetches the Pi's file when it has one
  (resumable); a sound-only copy never stands in for a video.
- **Deleting** a download on the phone deletes its copy at home too (`DeletesAtHomeToo`), unless the phone kept
  it (locked). Signing out of YouTube asks the Pi to forget the handed-over sign-in.
