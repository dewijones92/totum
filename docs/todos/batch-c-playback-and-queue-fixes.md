---
title: Playback and queue fixes from the reports of 21-24 Nov (v0.1.944)
kind: todo
status: built 2026-11-27, Batch C task U (unit tests; screenshots on totum-api35; nothing run on the Fire tablet or the Pixel 4a)
area: playback/queue/downloads
priority: high
requested: 2026-11-24
updated: 2026-11-27
---

# Playback and queue fixes from the reports of 21-24 Nov (v0.1.944)

Six items, each checked against the code before it was changed. Decision for 2 and 3:
[ADR 194](../adr/0194-best-means-the-best-this-device-decodes-and-a-failure-says-why-and-offers-a-way-out.md).

## Done

- [x] **Up next lists items the queue will skip** (20261121T061753). `skipWhy` is the one predicate behind
  `nextAfterThePlace` and the player's rows; skipped rows are dimmed and labelled, the header counts them
  ([ADR 74](../adr/0074-the-queue-passes-over-finished-talk.md)). The notification's Next line already used
  `nextAfterThePlace`, so it agrees by construction, and `UpNextViewTest` pins it.
- [x] **"Can't play this sound" when the phone can** (20261122T070821). `format_supported=YES` now reads "this phone's
  E-AC-3 decoder stopped on this sound"; a Pi file, torrent or Jellyfin item is offered "▶️ Play on …". The doubled
  toast: a repeat of the same item's problem within a second is dropped at the source, identical words are dropped
  at the toast, and either logs which.
- [x] **"Best" downloads ignore what the device can decode** (20261123T094934). `bestHeight()` caps "best" at what the
  device decodes; an already-downloaded file it cannot play says why and offers "⬇️ Get a 1080p copy".
- [x] **Landscape windowed player traps the page** (20261123T092003). The stage is capped at half the window's height in
  landscape and the handle claims downward drags only ([ADR 9](../adr/0009-rotation-follows-video-fullscreen.md)).
- [x] **"♾️ Endless radio" on every radio block** (20261124T202834). Only the live station says so; the rest read
  "📻 Radio · not topping up"; `radio.live` is in the state block ([ADR 27](../adr/0027-radio-repeat-and-shuffle-are-queue-features.md)).
- [x] **Diagnostics keep too little.** `radio`, `discover`, `download`, `fullscreen`, `codec` and `advance` are in
  `Breadcrumbs.KEPT_TAGS`; `home jobs` lines are logged on a change of state and as one count every 30 quiet reads
  (it was 29% of one report's ring).

## Open (Dewi's call)

- **What happens to an old radio block's remainder.** A radio block whose station was replaced is still in the queue and
  still plays by queue order once whatever was ahead of it ends; it just no longer claims to be endless. Whether it
  should be removed, collapsed or played last is his decision, and nothing was changed.
- **The "At home" refusal before the tablet download** (report 20261123T094934): the log showed the Pi refusing
  ("Not signed in") and the tablet then fetching the file. Nothing in `HomeDownloads.start` falls back by itself, so the
  second download was probably a second tap; unconfirmed. The refusal line now says nothing falls back.
- **Device checks owed:** the Fire tablet downloading "best" (expect 1080p), a Dolby file's decoder failing on a real
  phone, the landscape stage at `wm size 1920x1200` on the tablet itself.

## Repo files worth having

- A `tools/emulator` script that sets `wm size`/`density` for the three geometries (1080x2400, 320x640@160, 1920x1200) and
  puts them back, so a layout check is one command.
