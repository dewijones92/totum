---
title: 🏠 Downloads at home (the Pi fetches; the phone copies on Wi-Fi)
kind: feature
status: refining
area: downloads
updated: 2026-10-31
---

# 🏠 Downloads at home

When the phone's signal is poor, choose 🏠 At home in the ⬇️ question: the home server downloads it with the app's
own code (torrents, podcasts, YouTube with your sign-in), you can play it from home straight away, and the phone
copies it over the next time it is on Wi-Fi. ADR 110.

## What you see

- ⬇️ asks where (📱 On this phone / 🏠 At home); on mobile data 🏠 is suggested; "remember" keeps it.
- Rows: 🏠 Downloading at home · 45% → 🏠 At home · copies to the phone on Wi-Fi → the usual offline mark.
- The progress strip and a notification while it runs; "🏠 Ready at home" when done.
- Library → Downloads → 🏠 At home: every job, with Retry, Cancel and Delete.
- Deleting the download deletes both copies. Under 20 GB free at home a job is refused with the reason.

## Seams

- Phone: `downloads/home/` (`HomeDownloads`, `HomeFollower`, `HomeNotifications`, `DeletesAtHomeToo`),
  `DownloadChooser`/`EpisodeDownloads` (where), `PlaceChips`, `LocalHomeJobs`, `HomeSection`.
- Shared: `HomeJobsClient`/`HomeJobsApi` and the wire types in `core/data/.../home/`, `HomeCopyFirst`,
  `routeNow(atHome = …)`, `PhoneCodecs`/`VideoCodecFamily`.
- Home: `totum serve` (`HomeDownloads`, `HomeRoutes` in the CLI), `server/totum-fetch`.

## Status

Built and unit-tested on both sides; the service was proven on the laptop with real YouTube and podcast jobs.
