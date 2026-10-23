---
title: "Now playing" drifted from what was playing after quick skips
kind: todo
status: done 2026-10-23
area: queue
priority: high
requested: 2026-10-22
updated: 2026-10-23
---

# "Now playing" drifted from what was playing after quick skips

Seen on the emulator on 2026-10-22 (16 rapid ⏭: the cursor on index 20 while another item played) and reproduced on
2026-10-23 with 11 media-key ⏭ over a 14-video queue: the presses were queued and run **one at a time**, each waiting
for the previous video's whole lookup (15–18 s under the bot check), so the queue marched one video every ~17 s, played
each skipped-over video for a moment, and kept its cursor one step ahead of the player the whole time.

Fixed: media keys, the notification and the app's ⏭/⏮ all go through `QueueSkips`, which launches every press at once;
the newest-play guards already in `PlaybackQueue.route` and the launcher mean only the last press plays. Six quick
media-key ⏭ now move the cursor to index 6 in 2.5 s and the player goes straight to it; nothing in between plays. A skip
overtaken while it started logs "was skipped past while it started" instead of "advanced". Test:
`RapidSkipsLandOnTheLastPressTest` (red against the serial version: cursor 1, not 3).

**Changed after the batch review (2026-10-23):** launching every press still looked up every video passed over,
each through the polite gate. `QueueSkips` now gathers presses that come within 500 ms of each other and moves the
queue once by their total (`playNextInQueue(count)`, `step(landOn)`), passing over the items in between without
starting or resolving them. Three quick media-key ⏭ on totum-api35: "skipping 3 on at once", 0 → 3, and the only
lookup was the item landed on. `RapidSkipsLandOnTheLastPressTest` now also asserts that only that item is
extracted.
