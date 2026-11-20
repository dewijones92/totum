---
title: Long ones: unwatched videos over 20 minutes from what you follow
kind: feature
area: library
status: built 2026-11-20 (unit tests green where listed; not yet seen on a phone)
updated: 2026-11-20
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
- Left out: anything under 20 minutes, Shorts, a live stream that is on (or not started), anything you have started or finished
  (on this phone or on YouTube), anything 🙅 Not for me, and anything from a channel you do not follow.

## How it works

`LongOnes` (`app/…/longones/`) keeps one `StateFlow<LongOnesState>` and one `refresh`, at most one at a time.

1. **Pool, from caches only** (`longOnesPoolOf`): the newest upload per followed channel (`ChannelLatestUploads`' Room table), the
   cached Subscriptions feed, the stored episodes of followed shows. Merged by video id, keeping any known length.
2. **Eligibility:** not in `rowPlayStates` (any progress), through `NotForMe`, not live now by the registry (`liveTagOf`).
3. **Pick** (`pickLongOnes`, `:core:domain`): newest first, ≥ 20 min, ≤ 2 per source, ≤ 12; hands back the unknown-length
   items that could still change the answer.
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

## Tests

See [the tests index](../tests/_index.md), 2026-11-20 entry. Nothing here is covered by an instrumented test yet, and nothing has
been seen on a phone.

## Known limits

- Its depth is the newest-upload table, refreshed only when the All subscriptions page checks channels (ADR 185).
- A newer upload from a channel can push an older, already-listed one out of the list when its length is found; rows are not
  held in place the way a feed is (ADR 98) because the list is a ranking, not a feed.
