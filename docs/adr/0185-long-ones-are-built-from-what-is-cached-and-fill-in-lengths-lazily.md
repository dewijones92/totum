---
title: Long ones are built from what is cached, and fill in lengths lazily
kind: adr
status: accepted
updated: 2026-11-20
---

# 185. Long ones are built from what is cached, and fill in lengths lazily

- Status: Accepted
- Date: 2026-11-20

## Context

Dewi, 2026-11-20: a section of long-form videos he has not watched, things that "won't blast my dopamine receptors".
His decisions: from his **subscriptions only**, over **20 minutes**, **unplayed**, about **12 items**, as a Library tile
("🧘 Long ones") opening its own page.

Three facts shaped the design, all checked on 2026-11-20:

- **A channel's RSS feed has no duration.** Totum already reads every subscribed channel's RSS (ADR 94, `ChannelLatestUploads`)
  and keeps the newest upload per channel (1,622 channels, so up to ~1,600 rows).
- **The WEB `next` call that dates a video (ADR 94, `PublishDays`) does not say its own length either.** Probed with a
  public video: 466 KB, and every `lengthText` in it belongs to a *related* video.
- **The ANDROID `player` call does** (`videoDetails.lengthSeconds`, `isLiveContent`), and a refused video (age gate,
  members) still carries its `videoDetails`. One probe answered 195 KB, so it is not free: ~100 of them is ~20 MB.

Dewi's real state (the 20 Nov dump, read for shape only): 1,622 followed channels and **1 followed podcast**; of 1,336 queued
ordinary YouTube videos with a known length, 460 were over 20 minutes. His queue is a biased sample, so that 34% is a ceiling on
how many unknown videos will need a look-up per long one found, not an estimate.

## Decision

**What qualifies** (`pickLongOnes`, `:core:domain`, pure): a dated item of kind STANDARD or WAS_LIVE (a stream that ended is an
ordinary long video) of at least 20 minutes, not a Short (by kind or by a `/shorts/` link), not a live stream still going
(by kind, or by the live registry, ADR 132, which also knows a video that has not started), not already started or finished
(`rowPlayStates`, the account-aware map every row uses, so a video half-watched on the YouTube site is left out too), and not
under 🙅 Not for me. **Newest first, at most 12, at most 2 from one source** (a channel by its `UC…` id whichever listing the
video came through; a podcast show by its feed). The cap, not a filter, is what keeps it calm and varied.

**Where candidates come from, all of it already cached** (`LongOnesPool`): the newest upload of every followed channel (the
Room table the All subscriptions page fills), the cached Subscriptions feed (about 45 to 80 recent videos, with lengths), and
the stored episodes of followed shows. Duplicates by video id merge, keeping a length from whichever listing had one. Nothing
is fetched to build the pool.

**Where a length comes from, cheapest first** (`LongOnes.lengthOf`): the item itself (feed items and podcast episodes carry one);
`VideoLengths`, a file of lengths kept for good (a video's length never changes; the feed's lengths are kept into it too);
an earlier full look-up of the video (`VideoResolver.knownLength`, the lookup store, even after its URLs expired); and only then
YouTube.

**Asking YouTube** is `PlayerStreams.lengthCheck` / `VideoResolver.lengthCheck`, the sibling of `liveCheck`: the anonymous
`player` call parsed by the same parser, **without** solving `n` for the URLs (that is for playing). It shares one fetch-and-parse
with the second-opinion player, so the bot-check back-off (`BotBackOff`) and logging are the existing ones; a bot check is
"unknown", never a verdict on the video. It also tells the live registry what it sees (live now, or a stream that ended).
The answers: a length (kept for good); live now or not started (set aside an hour); the video says it has none (set aside six
hours, not kept); could not be asked (set aside 15 minutes).

**Lazily, and bounded.** A refresh builds the list from the cache first and publishes it, then walks the candidates newest first
looking up only the lengths that could change the list (an unknown ahead of the 12th pick, from a source not already at its
cap), 4 at a time in the polite gate's **background lane** (so never more than half the gate's slots, ADR 51), at most **40 asks per
refresh**, and only on an **unmetered** network (on mobile data the page says "📶 Waiting for Wi-Fi: N more video lengths to
look up" and shows what the cache can already answer). Lengths are kept, so each later refresh asks only about videos newer
than it has seen; a list that needs to reach further back than 40 asks gets there over successive refreshes.

**When it refreshes**: when the page opens and on its 🔄 (through the progress strip), when the app opens on an
unmetered network, and in the existing 2-hourly `new-uploads` job. No loop of its own. The channel RSS table is still filled
only by the All subscriptions page (see Consequences).

**Podcasts: included, and the cap is the answer.** Unified is the default. A podcast episode is nearly always over 20 minutes,
but a show is one source, so at most 2 episodes of it appear, however many shows are followed; at Dewi's scale (1 show of 1,623
subscriptions) it cannot flood the list. Episodes carry their length in the feed, so they cost no look-up. *This is a
recommendation Dewi can veto; the alternative is to drop `LongOnesPool.episodes`.*

**Does it obey ADR 19's dopamine-fast gate? No, it shows.** ADR 19 hides the Videos feed because it shows *new stuff* when he is
fasting. Long ones is the calm alternative he asked for, is capped, is only what he follows, and sits behind a deliberate tap on
a Library tile (Groups, which are also new uploads, already show there). The tile carries no 🆕 badge and no pulse (ADR 95's
pulse is deliberately not reused). *Recommendation, Dewi's to veto; gating it would mean reading
`feedHiddenUntilAsked` in `LongOnesScreen`.*

**The spoiler shield (ADR 141)** needs no code here: the page draws every row through `MediaItemRow`, which reads the one title
seam (`ItemFace`), so titles, pictures and lengths of shielded channels are hidden the same as everywhere. The page also takes
the same sort-and-filter sheet as every other list header (ADR 66).

**The tile** (🧘 Long ones, in the Library tile column under Trip mode) says what it is and, once a list has been built this
launch, "✅ N ready". It does no work to say so: it reads the last built list.

## Consequences

- **At Dewi's scale** (1,623 subscriptions): opening the page reads the table, the feed cache and one podcast's episodes (a few
  thousand rows, no network) and publishes within the time of one Room read; look-ups then run for at most 40 asks (~8 MB at the
  measured size, a few seconds at the gate's 2 per second plus 4 in flight). It never starts the 13-minute trickle of 1,609 RSS
  fetches. Steady state asks about the videos published since the last refresh.
- **The newest-per-channel table is the pool's depth.** It is as fresh as the last time the All subscriptions page checked
  channels (every 6 h when opened). Scheduling that check is *not* done: it is ~13 minutes of trickle, past a WorkManager
  window, so it would need its own design. Until then Long ones is as current as the Subscriptions feed cache (refreshed when
  the Videos tab loads) plus the table. This is the open item Dewi may want to take.
- A video's persisted length is kept by id in `files/video_lengths.json` (~25 bytes each); `KeptFacts` (new, shared with
  `PublishDays`, which was refactored onto it) owns the read, batched write and atomic rename.
- Reports carry `longones.lastRefresh` (the whole line below) and `longones.chosen` (id@length). Every refresh logs
  `[longones] refresh (why): pool=N (uploads= feed= episodes=) eligible=E (left out: played= notForMe= liveNow=)
  lengthsKnown= askedYouTube= (known= liveOrUpcoming= none= unknown=) chosen=C/12 tooShort= heldBackByTheCap=
  lengthsStillUnknown= waitingForWifi= in Nms`, every ask `length of <id>: …s via … in Nms`, and the pool read.
- Tests: `:core:domain` `LongOnesTest` (the minimum to the second, the 2-per-source cap and the next channel filling in, the
  limit, Shorts by kind and by link, lives, a ended stream, undated, look-up list, a source at its cap not worth a look-up,
  duplicates, one channel through two listings, a podcast show as one source); `:app` `LongOnesTest` (lengths from the feed with
  no ask, a look-up kept and not repeated, the stored-lookup fallback, newest-first and stop-when-full, the per-refresh budget,
  the cap over look-ups, Shorts and lives never asked, a live answer set aside, played / half-played / Not for me left out,
  mobile data asks nothing, no-look-up refresh, podcasts capped, one refresh at a time, the same video from two listings),
  `VideoLengthsTest` (kept across a restart, set-aside windows, never-kept none, a throwing ask); `:core:video`
  `TheLengthCheckReadsALengthWithoutSolvingAnythingTest` (every answer, no `n` solve, bot check backs off, backing off asks
  nothing), `LookupsSurviveRestartsTest` (a length from a stored lookup after its URLs expired).
