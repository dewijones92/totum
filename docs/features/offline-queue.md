---
title: Offline queue
kind: feature
status: shipped
area: downloads
updated: 2026-08-31
---

# Offline queue

Everything in the queue is fetched as audio automatically, so the queue plays with no signal —
and the app says plainly how far along that is.

Dewi, 2026-08-02: *"I expect everything in the queue to have an auto download for audio and for
it to work offline … I expect the gui / labels etc to be very very clear"*.

## What happens

`QueueAutoDownloader` watches the queue and fetches the audio of every entry. Playback prefers
the local file — decided by `routeNow` in `:core:domain`, **for both pillars** — so a downloaded
item never touches the network. Nothing is deleted automatically: leaving the queue keeps the
file, and it is removed from Library like any other download.

**Audio only, video on request.** Small, quick, and it matches how a queue is used. A full video
stays a deliberate per-item choice from the row menu.

**And only what HAS an audio-only form.** A torrent does not: the home server's audio is a live HLS
playlist, so `audioOnly = true` fetched the whole film instead (proven on a device 2026-08-06,
`copy=full`). Films are therefore left for a deliberate tap, and the banner counts them as
`2 to download by hand` rather than promising a fetch that is never coming.

## Three things were wrong, and only one was the machinery

**It only ran on Wi-Fi.** The default was Wi-Fi-only, so the queue was ready offline exactly when
you were somewhere you did not need it, and the pause was completely silent. It now downloads on
any network; the setting remains for whoever wants it, and the queue states when it is what is
holding things up.

**It was never sequential**, despite its own comment saying so. `DownloadManager.download`
launches into its own scope and returns at once, so the loop fired the whole queue together —
report 0.1.313 caught **nine at once**, each crawling, competing with playback for the
connection. It became one at a time, and is now **three at a time** — see *Several at once*
below. Manual downloads are untouched either way: a tap still starts immediately rather than
queueing behind seventy background fetches.

**Nothing showed readiness.** A per-row headphones glyph was the only signal, so "is my queue
ready?" could only be answered by scrolling 77 rows and counting.

## And a fourth, found on a plane (2026-08-06)

The whole feature did not work for **videos** — which is nearly everything in the queue. Playback
asked the download store for a local copy on the podcast branch and never on the video branch, so
a downloaded YouTube item was refused in airplane mode with its file on the disk. Fixed by giving
both pillars one routing decision (`routeNow`); the full account, including the deliberate
"an audio-only copy does not replace the picture while you are watching" rule, is in
[`../todos/downloaded-video-not-played-offline.md`](../todos/downloaded-video-not-played-offline.md).

Also worth knowing: an item YouTube serves but will not let yt-dlp download (members-only —
Novara's `AD FREE` uploads) can play with signal and never offline. The app marks these
`Online only` rather than pretending.

## Several at once, and it keeps going in the background (2026-08-31)

Dewi: *"background downloading should work for multiple files in parallel and should be rock
solid"*. Five changes, four of them about the second half of that sentence.

**Three lanes, not one.** `maxParallel` lanes claim entries from one ordered channel, so queue
order — and the playing item's place at the front of it — still decides who goes next. Three
rather than "as many as there are": these share one connection with whatever is playing, and
unbounded parallelism is what left nine crawling in 0.1.313. Most of a fetch is a yt-dlp resolve
waiting on YouTube rather than bytes moving, so three lanes drain an 80-item queue several times
faster without taking the pipe from playback. `awaitSettled` is what bounds it — a lane holds its
item until it finishes, so the limit is real.

**The process is held open while downloads run.** Downloads live in the application scope, which
lasts exactly as long as the process, and a backgrounded app with nothing playing is a cached
process Android reclaims whenever it wants the memory. Playback had a foreground service and
downloading did not, so "download the queue, put the phone in your pocket" worked only while music
happened to be playing. `DownloadKeepAliveService` (`dataSync`) is started the moment a download
begins and stopped when the last one ends; it adopts the progress notification the notifier
already posts, so there is one notification, not two. A refusal — Android 12+ blocks a foreground
service started from the background, Android 15 caps `dataSync` by the day — is logged and
downgrades to the old behaviour rather than crashing.

**A download the app died during is now retryable, not gone.** It used to be deleted at the next
launch, on the reasoning that its coroutine was gone. True, but the row vanished with it, the
partial file became bytes nothing pointed at, and nobody was told. It is recorded
`Failed("the app stopped before the download finished")` — worded so `isPermanent` cannot match
it — so Library offers Retry and the next automatic pass asks again.

**Part-fetched bytes are kept and continued.** Bytes land in a `.part` beside the target and only
move into place when whole, so a partial file can never be mistaken for a download. Next time,
`Range: bytes=N-` continues it. Appending happens **only** on a `206` from exactly the offset
asked for; a `200` (server ignored the range) starts clean and a `416` (the part outlives the
resource) drops the part. The signed-in YouTube fallback route is deliberately constructed
`resumable = false`: it re-resolves its URL each attempt and can be handed a different audio
format, and splicing two formats produces a file that will never play and that no retry can
detect.

**The gate is read before every item.** It used to be read once per pass, and a pass is the whole
queue — so a queue that started on Wi-Fi carried on over mobile data for the rest of it, and
switching automatic downloads off only took effect on the next one.

The pass also runs under `collectLatest` now, so dragging something to the top of the queue takes
effect at once instead of after the pass in flight finishes. Cancelling a pass costs nothing: the
downloads themselves live in the manager's scope and carry on, and the next pass skips whatever is
already in flight.

### The bug parallelism exposed within a minute

On the first device run, three queued videos started **six** downloads. `download` asked the
*store* whether an item was already being fetched, and the store is Room: its first `Downloading`
row lands milliseconds later, so two lanes inside that window both read `NotDownloaded`, both
claimed the item, and two coroutines wrote the same file for twice the data. The claim is now
taken from the in-memory job map under the mutex that guards it.

It was reachable before parallelism too — any two things asking at once — which is why the second
test for it shares three links into the app in quick succession (Dewi: *"make sure this works also
by sharing to the app multiple urls in quick succession"*). On a device that path produced two
`already fetching … — not starting a second` lines, i.e. two duplicate downloads prevented, out of
three shares.

## What you see now

| Where | What it says |
|---|---|
| Queue banner | `All 77 ready to play offline` · `62 ready offline · 2 to download by hand` · `73 ready offline · 4 can't be downloaded` · `60 of 77 ready offline · 17 still to fetch` · `Waiting for Wi-Fi to download 9 items` · `Automatic downloads are off · 5 items not saved offline` |
| Queue row, fetching | `Downloading 42%`, or `Downloading…` when the server sends no length |
| Queue row, impossible | `Online only`, in words |
| Library | An in-progress section at the top, with a bar **and** a percentage |

`OfflineReadiness` (`:core:domain`) does the counting, so the screens only render. A **retryable**
failure counts as *waiting*, not as a problem — the app is still trying, and asking for a decision
that is not the person's to make would read as a broken queue on a flaky connection.

Items that can never download — members-only, removed, region-blocked — are **kept and marked**,
not removed. They still play with signal, and deleting somebody's queue items to make a number
tidy is not the app's call.

## Tests

- `OfflineReadinessTest` — the counting, including the case that bit: downloaded items plus
  permanent failures must read as *settled*, not as forever in progress.
- `OfflineSummaryTest` (instrumented, runs on CI's emulator) — the wording itself, because that
  is what a person actually reads.
- `QueueAutoDownloaderTest` — that lanes are used and that the limit holds, that one lane still
  means one at a time, that a never-settling download does not wedge the queue, and that dropping
  onto a disallowed network stops a pass part-way.
- `DefaultDownloadManagerTest` — two callers claiming one item start one download, and a download
  interrupted by the app stopping is left retryable with its request intact. The first needs a
  store that commits a moment late, as Room does; against a synchronous one the race is unreachable
  and the test passes against broken code.
- `AnInterruptedDownloadResumesTest` — resume against a real HTTP server: the range asked for,
  progress counted from what is already on disk, a server that ignores the range, a 416, a dropped
  connection keeping its bytes, and the non-resumable route never sending a range.
- `DownloadsHoldTheProcessOpenTest` (instrumented, no network) — the keep-alive is genuinely a
  *foreground* service and is released afterwards. Nothing on the JVM can prove that.
- `AutoDownloadFetchesTheAudioTest.severalQueuedItemsAreFetchedAtOnce` and
  `SeveralSharedLinksAllLandTest` (both live) — several really do run at once in the real graph,
  and three links shared in quick succession all land and are each fetched exactly once.
- `PlayRouteTest` + `OfflineSkipsUnavailableTest` — the routing decision itself, per pillar, per
  copy variant, online and off. Reverting the 2026-08-06 fix turns 9 of them red.
- `OfflineQueuePlaybackTest` and `LiveDownloadedVideoOfflineTest` (instrumented, radios genuinely
  off) — a downloaded **video** plays from its file with no network; the live one downloads a real
  YouTube video through yt-dlp first, via CI's residential egress.
