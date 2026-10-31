---
title: Downloads at home — the Pi fetches with the app's own code
kind: adr
status: accepted
updated: 2026-10-31
---

# 110. Downloads at home: the Pi fetches with the app's own code

- Status: Accepted (the Pi's service is built; the phone side is next)
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
- **The phone decides the format.** A job carries the item (the backup format), Sound or Video, the height ceiling,
  and the phone's decoders (`PhoneDecoder`: family, highest height, hardware), so the Pi picks what that phone plays
  (`PhoneCodecs`, one `VideoCodecFamily` table shared with the phone's own check).
- **Torrents are read from TorrServer directly** (`TOTUM_REWRITE`, the public `/ts/` address rewritten to
  `gluetun:8090` on the compose network), so the Pi needs no device token for its own server.
- **Space:** files land in `/home/pi/ext_files/media/totum`; a job is refused with "❌ Not enough space at home"
  under 20 GB free; nothing is deleted except by the phone.

## Consequences

- Proven on the laptop on 2026-10-31: a YouTube job with the handed-over sign-in fetched 480p video + audio through
  the signed-in route (49 MB in 5 s), a podcast job over HTTP, ranges served (206, 200, 416), the list and sign-in
  kept across a restart, delete removing the file.
- One YouTube sign-in now lives in two places (Dewi's choice): 600 on the Pi, never logged, forgotten when the
  phone signs out.
- yt-dlp in the container updates itself at start, like the app's; the CLI is installed from a release, so the Pi
  runs only released code.
