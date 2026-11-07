---
title: Trip mode: fill the phone before you leave
kind: feature
area: downloads
status: built 2026-11-07, Batch A (tests owed; not run on a device)
updated: 2026-11-07
---

# 🧳 Trip mode

Decision: [ADR 149](../adr/0149-trip-mode-fills-the-phone-through-the-existing-download-paths.md). Backlog item:
[todos/trip-mode](../todos/trip-mode.md).

## What a person sees

- **Library → the 🧳 Trip mode tile** (under Kid TV). Off, it reads "🧳 Leaving soon? Get the phone ready". It opens the
  page.
- **The page:** "When are you leaving?" (In 1, 2, 4 or 8 hours; 2 is chosen) and "How many from each group?" (3, 5 or
  10; 5 is chosen), then **🧳 Get ready**. Armed, the page and the tile say "🧳 Leaving 17:30" and, as it goes,
  "✅ 12 of 18 queued items are on the phone", "⬇️ 4 more on the way", "🏠 2 files from the Pi are being copied",
  "⚠️ 1 item skipped: a film, a torrent, or one that cannot be fetched", "❌ 1 item failed and will not be asked for
  again", "📶 Waiting for Wi-Fi: the phone fetches nothing on mobile data" and "⚠️ Under 2 GB is free, so nothing more
  is fetched". **⏹️ Stop trip mode** ends it.
- **While it runs**, on Wi-Fi, the phone fetches the next N unplayed items of every queue group (and the ungrouped run)
  as one task in the progress strip, "⬇️ Downloading 🧳 your trip", with Cancel and a result toast. Cancelling it also
  switches trip mode off. Items are sound unless the person's remembered download choice is a video quality.
- **The Pi:** queued items the Pi has finished, and any other finished file on the Pi that fits, are copied to the phone
  through the same "📥 Copy to the phone now" path as the Pi's own menu (never one the person deleted from the phone and
  kept at home).
- **It never starts** a torrent or film, an item with nothing to fetch yet, one that failed for good or twice, or
  anything when under 2 GB is free.
- It ends at the departure time. There is no notification; the tile shows readiness while the app is open.

## Seams

`trip/TripPlan` (pure: `planTrip` over `aheadFor`), `trip/TripMode` (arming, stored `TripSettings`, the passes, status),
`trip/TripWiring` (ports from the container: queue, download states, Pi jobs, `HomeDownloads.copyNow`,
`EpisodeDownloads.fetchBatch`, free space, the strip's Cancel), `jobs/TripWorker` (a `PeriodicJob`, 15 minutes,
unmetered), `ui/trip/TripScreen` and `TripText`, `ui/library/TripTile`. `aheadFor` (`downloads/home/HomeAhead`) gained a
`groupOf` parameter so the ungrouped run counts as a group; `EpisodeDownloads.fetchBatch` was split out of `start`.

## Diagnostics

`trip` lines: `trip mode armed from <why>: leaving in N min (…), N per group`, `trip pass: A of B wanted on the phone,
C on the way, to fetch D, copy E queued and F other from the Pi, skipped G, failed H, low space=…` (logged when it
changes, with why), `trip: asking for N download(s) as sound: "…"`, `trip: copying N queued and M other file(s) from the
Pi`, `trip: not fetching "<title>": <reason>` (once per item), `waiting: this network is metered …`, `a trip batch is
still running …`, `trip mode off (<why>); the last pass said …`. Jobs: `trip-mode starting …`. The report's state block
has `trip.armed`, `trip.departureAtMs`, `trip.perGroup`, `trip.progress`.

## Not done / needs a device

- Not run on a phone. First checks: arm with a queue of mixed groups on Wi-Fi, switch to mobile data and back, kill the
  app and see the 15-minute job carry on, and arm with a Pi that has finished files.
- No notification at departure ("✅ ready"); the tile is the only readout.
- The hour and count choices, the 2 GB reserve and two attempts per item are the builder's calls. The two attempts apply
  to fetches, Pi copies and other Pi files alike, and are saved with the trip, so a restart does not reset them.

## Tests owed

A Compose test of the tile and page; a live run; `EpisodeDownloads.fetchBatch` through the download manager with a fake.
`TripPlanTest` and `TripModeTest` cover the plan and the controller.
