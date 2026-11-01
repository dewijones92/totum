---
title: 🏠 Downloads at home (the Pi fetches; the phone copies on Wi-Fi)
kind: feature
status: refining
area: downloads
updated: 2026-11-01
---

# 🏠 Downloads at home

When the phone's signal is poor, choose 🏠 At home in the ⬇️ question: the home server downloads it with the app's
own code (torrents, podcasts, YouTube with your sign-in), you can play it from home straight away, and the phone
copies it over the next time it is on Wi-Fi. ADR 110.

## What you see

- ⬇️ asks where (📱 On this phone / 🏠 At home); on mobile data 🏠 is suggested; "remember" keeps it.
- Rows: 🏠 Downloading at home · 45% → 🏠 At home · copies to the phone on Wi-Fi → 📥 Copying from home 70% → the usual offline mark.
- The progress strip and a notification while it runs; "🏠 Ready at home" when done.
- Library → Downloads → 🏠 At home opens with the home server's space, like the phone's: "🏠 Using 1.8 GB at home ·
  139 GB free" and a meter (Totum's finished downloads, and the disk's free space, from every job-list read).
- Library → Downloads → 🏠 At home: every job as an ordinary item row (poster, ⋮ menu, tap to play from home), with
  📥 Copy to the phone now, Retry, Cancel and Delete; a job that will not copy by itself (kept at home, or the
  setting off) says "🏠 At home · stays there until you copy it".
- Any item's menu, once it is at home: 📥 Copy to the phone now (any network), 🗑️ Delete from the phone, keep at home
  (it then stays home until copied again by hand), 🏠 Delete at home (keep on the phone). Rows say "🏠 Also at home".
- 🏠 Send home in any app's share sheet: a YouTube link or a magnet goes straight home, without opening Totum; a
  toast says "🏠 Downloading at home: …" or "❌ Not sent home: …" (`SendHome`, `SendHomeActivity`).
- A download on the phone offers 🏠 Also at home and 🏠 Move to home in its menu; a move deletes the phone copy once
  home has it. A video that finishes at home (torrent or YouTube) is filed into Jellyfin when Haiku sees a whole film
  or episode (ADR 111).
- A queue group's ⋮ → 🏠 Keep the next 2 (or 5) at home: the next unplayed members go home and are topped up as you
  listen; on the 📥 New group or a shuffled channel this fetches new uploads at home as they arrive (`HomeAhead`).
- Settings → Downloads → 🏠 Copy home downloads to the phone (on by default; off keeps everything home until asked).
- A finished torrent (film or episode) is filed into Jellyfin's movies or series library by Haiku, into the show's
  existing season folder, and Jellyfin is told; the app still plays, copies and deletes it there (ADR 111).
- Deleting the download deletes both copies. Under 20 GB free at home a job is refused with the reason.

## Seams

- Phone: `downloads/home/` (`HomeDownloads`, `SendHome`, `HomeAhead`, `HomeFollower`, `HomeNotifications`, `DeletesAtHomeToo`),
  `DownloadChooser`/`EpisodeDownloads` (where), `PlaceChips`, `LocalHomeJobs`, `HomeSection`.
- Shared: `HomeJobsClient`/`HomeJobsApi` and the wire types in `core/data/.../home/`, `HomeCopyFirst`,
  `routeNow(atHome = …)`, `PhoneCodecs`/`VideoCodecFamily`.
- Home: `totum serve` (`HomeDownloads`, `HomeRoutes` in the CLI), `server/totum-fetch`.

## Status

Built and unit-tested on both sides, and proven end to end on 2026-10-31 (v0.1.807 on the Pi, the emulator as the
phone): a podcast episode sent home (8.8 MB in 2 s), announced "🏠 Ready at home" and copied over Wi-Fi through
`HomeCopyFirst`; a YouTube video sent home as 480p handed the sign-in over, was refused by yt-dlp and fetched by the
signed-in route on the Pi (147 MB, n solved by Node), then replaced the phone's sound-only copy; deleting either on
the phone deleted the copy at home. Not yet proven live: playing from the Pi's copy before the phone has its own
(unit-tested; the same URL and token served the copy).

First real use (report 20261031T193811-51dfc9b5, v0.1.811): Toy Story 5 (1080p 10-bit x265, 1.96 GB) sent home from
the phone, done on the Pi in 7 minutes, copied to the phone over Wi-Fi in ~70 s. The phone's copy showed as a plain
"⬇️ Downloading 70%", which read as the Pi lagging; it now says "📥 Copying from home".
