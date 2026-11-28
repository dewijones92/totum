---
title: Long ones: unwatched videos over 20 minutes from what you follow
kind: feature
area: library
status: built 2026-11-20 (unit tests green; seen on totum-api35 with the real account, not yet on a phone)
updated: 2026-11-27
---

# 🧘 Long ones

Dewi, 2026-11-20: a calm list of long-form videos he has not watched, "things that won't blast my dopamine receptors", from his
subscriptions only. Decision: [ADR 185](../adr/0185-long-ones-are-built-from-what-is-cached-and-fill-in-lengths-lazily.md).
Follow-ups: [todos/long-ones-follow-ups](../todos/long-ones-follow-ups.md).

## What a person sees

- **Library → the 🧘 Long ones tile** (under Trip mode): "🧘 Unwatched videos over 20 minutes, from what you follow", and
  "✅ N ready" once a list has been built since the app started. No badge, no pulse.
- **The page** (🧘 Long ones, with the usual sort-and-filter and a 🔄): a one-line explanation, then up to **12** rows, newest
  first, **at most 2 from one channel or show**, each an ordinary row (thumbnail with its length, 🔴 tags, the spoiler shield,
  the item menu, Play on…). Tapping plays it. While lengths are still being looked up it says "⏳ N video lengths are still being
  looked up"; on mobile data "📶 Waiting for Wi-Fi: N more video lengths to look up". Empty: "📭 Nothing long and unwatched yet. It
  looks again whenever you open this page."
- Left out: anything under 20 minutes, Shorts, **any live stream: on now, not started, or ended** (since 2026-11-27, ADR 185: by
  kind, by the live registry, by the length check, and by a channel's Live tab; kept for good in `files/known_streams.json`),
  **any video over 4 hours** (a podcast episode has no upper limit), anything you have started or finished
  (on this phone or on YouTube), anything 🙅 Not for me, and anything from a channel you do not follow.

## How it works

`LongOnes` (`app/…/longones/`) keeps one `StateFlow<LongOnesState>` and one `refresh`, at most one at a time.

1. **Pool, from caches only** (`longOnesPoolOf`): the newest upload per followed channel (`ChannelLatestUploads`' Room table), the
   cached Subscriptions feed, the stored episodes of followed shows. Merged by video id, keeping any known length.
2. **Eligibility:** not in `rowPlayStates` (any progress), through `NotForMe`, not a live stream in any phase by the registry
   (`liveTagOf`) and not in `KnownStreams`; a look-up that finds a stream drops it from the same refresh.
3. **Pick** (`pickLongOnes`, `:core:domain`): newest first, ≥ 20 min, a video ≤ 4 h (`tooLong`), ≤ 2 per source, ≤ 12; hands back
   the unknown-length items that could still change the answer.
4. **Lengths**, cheapest first: the item; `VideoLengths` (kept for good, `files/video_lengths.json`, the feed's lengths too);
   the lookup store (`VideoResolver.knownLength`); then `VideoResolver.lengthCheck`, a lean anonymous `player` ask with no `n`
   solve, sharing the second opinion's back-off. 4 at a time in the polite gate's background lane, 40 asks a refresh, never on
   mobile data. Live / not started: set aside an hour; "no length": six hours; unanswerable: 15 minutes.
5. The list is published from the cache first and again after every batch of asks.

**Refreshes:** opening the page, its 🔄, 20 s after the app opens on an unmetered network, and the 2-hourly `new-uploads` job
(`NewUploadsWorker`). No loop of its own.

## Diagnostics

`[longones]` lines: the pool read (`subscribedChannels= subscribedShows= feed= uploads= episodes=`), each ask
(`length of <id>: …s via … in Nms`, or why not and for how long it is set aside), and one `refresh (why): …` summary with the
counts and the time. Report vitals `longones.lastRefresh` and `longones.chosen`. Tile and page taps are logged.

## Seen on the emulator (20 Nov 2026, `totum-api35`, signed in as Dewi, build 319244f1)

- First refresh, with 243 of the 1,622 channels checked: pool 243, **32 asks in 11.5 s** (30 lengths, 2 "live now"), 16 too short,
  **12 of 12 chosen**. Every refresh after asked nothing and took 223 to 506 ms (`[longones] refresh (…)` lines).
- The tile reads "✅ 12 ready" and wraps cleanly at **320x640@160** (the CI geometry) and 1080x2400@420; the page's rows are the
  ordinary rows, with lengths on the thumbnails and the "✅ subscribed" mark. At 320x640 the header title wraps over three lines
  beside its three icons (the sort, filter and refresh buttons and the cast button take the width), the same as other list
  headers; nothing is cut.
- A 9-hour "practice cam" stream that had ended was listed on 20 Nov as an ordinary video; that was reversed on 27 Nov (ended
  streams and anything over 4 hours are left out, ADR 185). Not yet seen on the emulator after the change.
- Screenshots (scratch, not kept in the repo): `library-tile.png`, `long-ones-page.png`, `long-ones-320x640.png`,
  `tile-320-step2.png` under the session scratchpad `longones/` folder.

## Tests

See [the tests index](../tests/_index.md), 2026-11-20 entry. Nothing here is covered by an instrumented test yet.

## Known limits

- Its depth is the newest-upload table, refreshed only when the All subscriptions page checks channels (ADR 185).
- A newer upload from a channel can push an older, already-listed one out of the list when its length is found; rows are not
  held in place the way a feed is (ADR 98) because the list is a ranking, not a feed.
