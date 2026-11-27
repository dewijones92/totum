---
title: Long ones are built from what is cached, and fill in lengths lazily
kind: adr
status: accepted
updated: 2026-11-27
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
  members) still carries its `videoDetails`. Probes answered 93 to 195 KB (a finished video 93 KB, a live one 142 KB), so it is not free: ~100 of them is ~10 to 20 MB.

Dewi's real state (the 20 Nov dump, read for shape only): 1,622 followed channels and **1 followed podcast**; of 1,336 queued
ordinary YouTube videos with a known length, 460 were over 20 minutes. His queue is a biased sample, so that 34% is a ceiling on
how many unknown videos will need a look-up per long one found, not an estimate.

## Decision

**What qualifies** (`pickLongOnes`, `:core:domain`, pure): a dated item of kind STANDARD (until 2026-11-27 also WAS_LIVE, "a stream that ended is an
ordinary long video"; see the amendment at the end) of at least 20 minutes, not a Short (by kind or by a `/shorts/` link), not a live stream still going
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

- **Seen on `totum-api35`, signed in as Dewi, 20 Nov 2026** (build 319244f1, the channel check 243 of 1,622 channels in): the first
  refresh took **11.5 s** (pool 243 uploads, 2 live ones left out by the registry, **32 asks: 30 lengths, 2 "live now"**, 16 too
  short, **12 of 12 chosen**, 0 held back by the cap); each ask took 110 ms to 2.7 s through the gate. Every refresh after it asked
  nothing and took 223 to 506 ms. A 9-hour "practice cam" that had ended was listed as an ordinary video then (reversed 2026-11-27, see the amendment); the two
  streams still on were left out. No YouTube refusal or bot check showed.

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

## Amended 2026-11-27: a stream that has ended is never a long one, and a video over four hours is not either

Dewi, 2026-11-27: a 9-hour ATP "Live Practice Stream" (an ended live) showed in 🧘 Long ones. Ended streams are long recordings
of nothing in particular, not the calm list he wanted, so the 2026-11-20 decision (an ended stream is an ordinary long video) is
**reversed**.

**Ended and live streams are left out, wherever they are known from** (`LongOnes`, with `KnownStreams` beside `VideoLengths`):

- **By kind:** `pickLongOnes` now accepts only `STANDARD`; `WAS_LIVE` joined `LIVE`, `SHORT`, `MUSIC` and `AUDIOBOOK` among the
  kinds that can never be a long one (`canBeALongOne`).
- **By the live registry (ADR 132):** any phase counts, live, not started or **ended** (`liveStream`, from `liveTagOf`), not
  only the first two as before. The registry forgets an ended stream at restart (it persists only live and upcoming ones), so
  every stream the registry or a kind names is also **kept for good** in `files/known_streams.json` (`KnownStreams`, id → why),
  and a kept id is never listed again.
- **By the length check:** `LengthCheck.Known(wasLive = true)` (the `player` call says the video was a live), `LiveNow` and
  `Upcoming` are streams. The answer is acted on **in the same refresh**: the video is dropped from the candidates, so it cannot
  appear in the list a moment after the ask that found it, and it is kept. (The filter by registry runs before the look-ups,
  so without this a video first learned to be an ended stream by the ask would still have been chosen on its freshly found
  length.)
- **By a channel's Live tab (ADR 178):** every video the Live tab lists (the first page and each page after it) is a live stream
  by definition, so `ChannelViewModel` hands its ids to `LongOnes.noteLiveStreams` (`ChannelReader.streamsListed`) and they are
  kept. This is the only place that knows a stream *without* asking YouTube about it, and it costs no request.
- Not done, on purpose: guessing from a title ("Live Practice Stream", "24/7"). The kinds and the check above are what the live
  check identifies; a title rule would drop ordinary videos that say "live" and still miss the rest.

**A video over four hours is left out, unless it is a podcast episode** (`LongOnesRules.maximumVideo`, 4 hours, a video being
`MediaItem.pillar == VIDEO`): the length rule is "at least 20 minutes", and nothing in a calm list is a nine-hour recording of any
kind (a very long stream whose live state nobody has seen yet, a full-day marathon). A podcast episode has no upper bound, as
long audio is what a podcast is. Exactly 4 hours stays; one second more goes. Such a video does not use up its channel's
two-per-source cap (it is counted `tooLong` and the next one from the channel is looked at).

Dewi's state, for scale: the 20 Nov dump held 1,622 followed channels and 460 of 1,336 queued videos over 20 minutes; the kept
set adds at most a few hundred ids at ~40 bytes each, read once into memory, written only when a new id arrives, so nothing here
grows with the number of refreshes. The cap is a comparison on a length already known, so it adds no ask.

**Logging:** the refresh line now says `liveStreams=` (what the kind and registry left out), `streamsFoundByLookUp=` and `tooLong=`;
each look-up that turns out to be a stream logs its id (`N look-up(s) turned out to be live streams, left out: …`) and each
newly kept id is logged with where it was learned (`kept N live stream(s) as never long ones (the live registry or the item's
kind | length check | Live tab)`).

Tests: `:core:domain` `LongOnesTest` (an ended stream is never a long one; over four hours left out and counted, exactly four
hours kept; a podcast episode of six hours kept; a nine-hour video does not use up its channel's cap); `:app` `LongOnesTest` (an
ended stream found by the look-up is left out of that very refresh and, after a restart, of the next without being asked again;
a video the Live tab listed is never chosen whatever length the feed gave it; a stream the registry knew stays out after the
registry forgets it; a nine-hour video is left out whoever says its length). Seen red first: `expected:<[four]> but was:<[practice,
four, five]>`, and with the in-refresh drop disabled `expected:<[talk]> but was:<[practice, talk]>`.
