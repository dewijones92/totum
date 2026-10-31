---
title: Download notifications
kind: feature
status: shipped
area: downloads
updated: 2026-10-31
---

# Download notifications

**Ask:** "in antennapod/newpipe there are notificaitons for downloading/downloaded/etcetc
maybe lets have notificatons for that".

Three notifications, on their own channel: **progress** while downloads run, **completed**
listing what landed, **failed** with the reason.

## One bar per running download, and a tap opens Downloads (2026-10-31)

Dewi: *"I can only see like one at a time … I want to see them all in progress in the progress bars
… click it and go into the app, downloads … if they're finished, go to the finished downloads"*.
The aggregate below is kept for what is QUEUED, and each RUNNING download (at most four, the
download slots) now has its own notification with its own bar and "⬇️ 56% · 90 MB of 158.2 MB",
under the "Downloading N" summary, which lists every running one and "⏳ N waiting for a free slot"
(`RunningDownloadNotifications`, `DownloadNoticeTracker.running/waiting`). Every download
notification opens Library → Downloads at its section (`DownloadsRequests`, extra
`totum.openDownloads`): running ones at ⏳ In progress, "Downloaded" at ✅ On the phone, "failed" at
❌ Failed. A dismissed "Downloaded" or "failed" no longer comes back on the next event: it is
re-posted only when its list changes. ADR 104.

## The one design decision worth defending

AntennaPod and NewPipe both notify **per download**. Totum does not — it aggregates.

That's not a shortcut, it's a consequence of a feature Totum has and they don't: the queue
**auto-downloads the audio of everything in it**. Queue a playlist of thirty and per-item
notifications mean thirty notifications for a single action. The aggregate is the
difference between a signal worth glancing at and something muted within a day.

So: one ongoing "Downloading N items" with combined progress, one "Downloaded N items"
listing them, one "N downloads failed" with reasons.

Related choices, same reasoning:

- **Low-importance channel, progress marked silent.** A download starting is routine when
  it's automatic; it must never buzz. Android's own channel settings then let the channel
  be muted entirely, which is why there's no in-app toggle duplicating that.
- **Failures are not silenced.** A silent failure is how you find out mid-commute that
  nothing was actually fetched.
- **Progress is indeterminate unless every active download reports a size.** Podcast
  enclosures frequently arrive without a content length, and a determinate bar built from
  partial information lies.

## Structure

The interesting part is pure and JVM-tested; the Android part is deliberately dumb.

| Piece | Where | Job |
|---|---|---|
| `DownloadEvent` | `:core:data` | A state change *with its item attached* |
| `DownloadManager.events()` | `:core:data` | Hot stream of transitions |
| `DownloadNoticeTracker` | `:app` | **All the judgement** — batching, aggregation, forgetting. Pure |
| `DownloadNotifier` | `:app` | Renders a `DownloadNotice`. No decisions |

**Why a new event stream:** `observeDownloads()` keys by `MediaItemId`, which is all a row
needs because it already has the item. Anything outside a list — a notification — has an
id and no way to get a title from it. Rather than make every consumer go looking, the
event carries the item. (The download *store* not holding titles is the same root cause as
[the Library showing only podcasts](../todos/library-downloads-podcast-only.md).)

**Batching rule:** a download starting while nothing is active begins a new batch and
clears the previous batch's results. Without that the completed list grows for the whole
session.

## Tests

`app/.../DownloadNoticeTrackerTest.kt` — 11 tests over the tracker: active/completed/failed
transitions, progress averaging, indeterminate when any size is unknown, batch boundaries,
accumulation within a batch, `NotDownloaded` being non-news, retry clearing a failure, and
repeated progress not duplicating a row.

## Verified on-device

Subscribed to a feed, downloaded an episode, and watched the shade through the whole
lifecycle: ongoing progress notification on `channel=downloads` with the episode title →
on completion the progress notification is **cancelled** and replaced by an `AUTO_CANCEL`
"Downloaded" notification.

One lint note worth keeping: the `POST_NOTIFICATIONS` check is written **inline** rather
than extracted into a helper, because lint's dataflow can't see through a helper and
suppressing `MissingPermission` would hide the real thing it guards.
