---
title: Trip mode fills the phone over Wi-Fi through the existing download paths
kind: adr
status: accepted
updated: 2026-11-07
---

# 149. Trip mode fills the phone over Wi-Fi through the existing download paths

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-05 (agreed idea): "I'm leaving in 2 hours" fills the phone over Wi-Fi with the next few of each queue group
plus whatever the Pi has ready, shown in the progress strip. The Batch A review added a rule: Pi files reach the phone
only through "📥 Copy to the phone now", since automatic copying is off by default
([ADR 126](0126-a-move-to-the-pi-sends-the-file-and-every-download-says-where.md)); trip mode must never be a fourth
download path.

## Decision

- **A trip is a time and a count.** The person says when they leave (1, 2, 4 or 8 hours from now) and how many to take
  from each group (3, 5 or 10; default 5). It is stored (`TripSettings`) so a restart keeps it, and it ends by itself
  when the departure time comes, or when the person stops it, or when they cancel its batch in the progress strip.
- **What is wanted is `aheadFor`**, the pure rule "Keep the next N at home" already uses: the next N unplayed members of
  each queue group after what is playing, with the ungrouped run as one more group (`aheadFor` gained a `groupOf`
  parameter for that). `planTrip` is pure and decides, per item: on the phone already, on its way, to fetch, to copy from
  the Pi, or skipped with a reason.
- **The phone's fetch is `EpisodeDownloads.fetchBatch`**, the batch the group menu's "download the episodes" runs (split
  out of `EpisodeDownloads.start`): one DOWNLOAD_BATCH task in the progress strip titled "🧳 your trip", four at a time
  through the download manager's own cap, result toast, Cancel. It uses the person's remembered sound-or-video choice, else
  sound, as the queue's automatic download does.
- **The Pi's files come only through `HomeDownloads.copyNow`**, the "📥 Copy to the phone now" path: queued items the Pi
  has finished, and any other finished file on the Pi that is not on the phone, not already on its way and not one the
  person deleted from the phone but kept at home (`kept`). Their sizes are counted against free space.
- **Never started for you:** a torrent or film (the queue's automatic download has the same rule, since "a deliberate tap
  still fetches it"), an item with nothing to fetch, a permanently failed item, an item that has had two goes. Under 2 GB
  free (the reserve) nothing more is fetched or copied and the page says so.
- **Wi-Fi only**, the same unmetered test the app uses elsewhere. On mobile data it waits and says so.
- **It looks again** when the queue or the Pi's jobs change and every minute while the app is open, and a 15-minute
  `PeriodicJob` (`trip-mode`, unmetered network) runs one pass when the app was not open, so a trip survives the process
  being killed. A pass is skipped while the trip's own batch is still running.
- **Surface.** A Library tile "🧳 Trip mode" shows what is happening (leaving time, "✅ N of M queued items are on the
  phone", more on the way, Pi copies, skipped, failed, waiting for Wi-Fi, low space); it opens a page to choose the time
  and count and to start or stop.

Dewi's words cover the idea, the Pi rule and the strip. The builder's calls, open to change: the hour and count choices
and defaults, the 2 GB reserve, two attempts per item, skipping torrents, copying every finished Pi file that fits rather
than only queued ones, the 15-minute job, the unmetered test, and no notification when the trip ends.

## Consequences

- Nothing is downloaded by trip mode unless a trip is armed; arming is one deliberate action.
- Two kinds of file can reach the phone that the person did not individually choose: the next few per group, and Pi
  files that fit. The strip and the page say how many, and Cancel in the strip stops the lot and switches trip mode off.
- There is no notification at departure: the tile and page show readiness. A notification ("✅ ready: 18 items") is the
  obvious next step and is not built.
- Not run on a device in this change (no device was touched); the 15-minute job and the Pi copies are untested live.

## Tests

`TripPlanTest` (12) and `TripModeTest` (11): next N per group and for the ungrouped run, after what is playing, played
items passed over, on-phone and in-flight not asked twice, torrents skipped, no-URL items skipped, permanent and
exhausted failures left alone, Pi copies for queued and other files within free space, the reserve, arming and passing
(Wi-Fi, a running batch, departure, strip cancel, stop, a past time, resume at launch) and the codec.

## Tests still to write

A Compose test of the tile and page; a live run on a phone (a real Wi-Fi to mobile switch, a kill and the 15-minute job,
a Pi with ready files); `EpisodeDownloads.fetchBatch` through the download manager with a fake.
