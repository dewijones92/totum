---
title: Crash and diagnostics reporting
kind: feature
status: shipped
area: infrastructure
updated: 2026-09-06
---

# Crash reporting

A crash on Dewi's phone used to be invisible — logcat only reaches the emulator attached
to this laptop. Now every crash, and every "that behaved wrongly", arrives on his Pi with
enough context to diagnose it without asking him anything.

## The seam

One rule shapes the design: **a crash is often the last thing the process does**, so
nothing is trusted to survive it. The report is written to disk inside the crash handler
and uploaded on the *next* launch — never at crash time. A failed upload keeps the file
and retries, so a crash on the Tube still arrives later. Verified: a genuine crash whose
upload hit a DNS failure was re-sent successfully on the following launch.

| Piece | Where | Job |
|---|---|---|
| `Breadcrumbs` / `Diag` | `:lib:common` | The rolling event trail, and the one call that logs *and* remembers |
| `installAndroidLogSink()` | `:app` | Routes `Diag` to logcat — the only `android.util.Log` use left |
| `CrashReporter` | `:app` | Uncaught handler; builds the report |
| `DiagnosticsStore` | `:app` | Pending reports on disk, capped at 50 |
| `DiagnosticsUploader` | `:app` | Sends pending reports at launch; deletes only on success |
| `tools/crashlog-server` | the Pi | FastAPI sink → files + SQLite index, behind Google auth |
| `app/…/diagnostics/DiagnosticSnapshot.kt` | the app | the `state` block of every report — playback, queue, disk, settings, account, network, outbound sync — built from cached values only, so it can never hang or throw (moved out of `AppContainer` 2026-09-06; keys unchanged) |

### Why the trail lives in `:lib:common`

It started in `:app` and that was wrong: the most valuable breadcrumbs come from the
lower layers — playback transitions, codec rejections, download failures. A trail only
the UI could write would miss exactly the lines that diagnose a bug. `:lib:common` is
pure JVM and api-exposed through `:core:domain`, so every module can feed it; a pluggable
`Diag.Sink` keeps Android out of it (silent by default, so tests and pure-JVM callers
need no setup).

## What a report contains

Verbose by explicit instruction (Dewi, 2026-07-25 — *"forget about PII or data
sensitivity … prioritise collecting data"*):

- **Identity:** app version, versionCode, **git commit** (so a report maps to code),
  build type, a stable install id.
- **The failure:** exception class, message, full stack trace, cause, thread.
- **The story:** the last 400 `Diag` events, timestamped and tagged — queue mutations,
  playback transitions, codec rejections, download start/done/failed, sync results.
- **The state at the moment it broke:** what was playing and its position, the whole
  queue, every setting, whether the network was metered.
- **The device:** model, Android version, ABIs, heap and system memory, free storage.
- **~150KB of logcat** — where the Media3 / MediaCodec lines live, which is what
  actually diagnosed this project's playback bugs.

**One security exception, which is not a privacy preference:** the YouTube OAuth tokens
are never read into a report. A token in a transmitted log is an account-takeover risk
rather than a disclosure of viewing habits. Trivial to include if ever wanted.

## Reading them

```bash
curl -s https://crashlog.333133333.xyz/latest              # newest report, pretty-printed
ssh pi@333133333.xyz 'cat /home/pi/crashlog-data/reports/*/*.json'   # works even if the service is down
```

The web index (`/`) filters by commit and exception and groups by exception; `/api/reports`
is the machine-readable list. Reports are stored as plain files first and indexed second,
so an unparseable payload is still kept and still visible.

## Not just crashes

Most of this project's bugs weren't crashes — they were wrong behaviour. **Settings →
Diagnostics → "Send diagnostics"** sends the same report with no crash involved, which is
how a "this played the audio-only file as a video" report gets diagnosed.

## A note on log volume

The silence detector enters silence every few seconds during speech. Logging each one
flooded the trail and evicted the useful lines, so it logs the first plus every 50th with
a running count — enough to prove detection works, quiet enough to leave room for signal.

The skip-silence speed change had the same problem and was worse: **239 lines, 59% of one
400-entry report**, leaving sixteen minutes of history in a buffer meant to hold hours —
precisely when a stall needed explaining. Same treatment: counted, every 50th logged.

That is the one real constraint on the "log generously" rule in `CLAUDE.md`. Anything firing
many times a second gets counted and logged periodically. Counted, never silent.

## Log the decision, not just the outcome (2026-07-28)

Dewi asked whether the playback position clears when switching bottom tabs. The honest
answer was that the diagnostics *could not say* — nothing recorded that a tab had been
switched — so the question was unanswerable rather than answered. His standing instruction
came from that and now governs this repo: err on the side of far more logging, because an
unlogged decision is an unanswerable question.

Four trails were added, each closing a specific blind spot:

| Trail | Answers |
|---|---|
| `nav` | which tab, when — the correlation everything else needs |
| `place` | where a screen was on entry and exit; if they differ, state was lost |
| `vm` | whether a view model was recreated (it is not — ruled out a whole class of cause) |
| `advance` | which of four reasons stopped an auto-advance |
| `queue` | *which operation* mutated the queue, not just the resulting snapshot |

The queue one is the subtle one. A cursor of `-1` is what **both** a deliberate "peek" and a
hydration-with-nothing-playing look like, and telling those apart decides whether an
auto-advance failure is a bug or by design. It was impossible to tell from a report; every
mutation now carries one word of intent.

`place` has a trap worth knowing: `entered` appears *before* the previous screen's `left`,
because `AnimatedContent` composes the incoming destination before disposing the outgoing
one. Not a bug, but it reads like one.

**This paid for itself the same day, twice.** One `nav` line found a root cause on the very
next report: an `extract` completed, the user switched tab 1.7s later, and nothing ever
played — because row actions ran playback on `rememberCoroutineScope()`, which dies with the
composition.

Then the `advance` trail answered "it didn't auto play next in my pocket" outright (the
setting was off) *and* exposed a second bug in the same report: the decision was not reached
until seven minutes after the item ended, when the app returned to the foreground. Snapshots
kept arriving throughout — so the contrast between the two trails is what localised it. A
lone trail says what happened; two trails disagreeing say *where*.

## Tests

`lib/common/src/test/kotlin/.../DiagTest.kt` — breadcrumb recording, sink routing, warn
formatting, oldest-first ordering, and buffer eviction at the cap.

Verified on-device end to end: forced crash → 154KB report on disk → DNS failure kept it
→ next launch uploaded it → indexed on the Pi with a 9-event trail, stack, and state.

## From a trail to a timeline (2026-07-27)

Dewi's ask: press a button and send "the last 30 mins of logs/analytics", including what
is downloading or streaming.

**The trail is time-bounded now, not count-bounded.** Nothing younger than 30 minutes is
dropped; at least 400 entries are kept regardless of age, so a quiet session still has
context; and 5,000 is a hard ceiling on memory. An entry is evicted only when it is
*both* past the floor and past the window — so age protects a line the count would have
evicted, and vice versa. A chatty minute of ticks can no longer push out the thing that
broke twenty minutes ago.

**`ActivitySnapshotter` writes a line every 30s** naming what is playing and where, how
long the queue is, and every download in flight with its progress. Transitions alone
answer "what happened" but never "what was it in the middle of" — a download stuck at 40%
for ten minutes produces no events at all, which is exactly when it matters. Silent when
nothing is happening, so an idle app spends none of the window.

**More paths instrumented:** subscribe / unsubscribe (with failures and unparseable
feeds), and every settings change, routed through one logged path so a report can say
*when* a setting changed rather than only its current value.

Verified on device: `[snapshot] playing "…" at 29346ms (running); queue=5; downloading=0`.

## Autoplay to the next item is traceable end to end (2026-07-31)

Dewi asked whether the advance is properly tracked. It was, for the *decision* — every refusal
already said why — but three things were missing, and each was a case where a report could not
answer an obvious question. All three verified on the emulator with the screen off:

```
[playback] ended at 18940ms of 18933ms — jNQXAC9IVRw "Me at the zoo"
[queue]    advanced from index 10 to 11 of 20 — "Learning C++ at 18 years old"
[advance]  jNQXAC9IVRw ended -> queue advance=true
[playback] playing at 608094ms — 3436ms of silence since the last item ended (SLOW handover)
```

- **The success now names where it went.** Only the refusals spoke, so an advance onto the wrong
  item read as `advance=true` with no clue which of sixty entries it landed on.
- **A skipped entry says which one and that it would not play.** `nothing playable after index 3`
  with a full queue previously named none of the items it had refused.
- **The handover is TIMED in wall clock.** The one number that says whether autoplay felt right,
  and the one number nowhere in a report: `ended` and `playing` both carry media positions, so a
  3-second handover and a 40-second one were indistinguishable. Now measured, flagged past 3s,
  and counted in Vitals as `playback.handovers` / `playback.handoverMs`.

The 3436ms above is itself the explanation working: that item was part-watched, so it took the
extraction path rather than SABR, and extraction costs seconds. The log now says so instead of
leaving a gap to wonder about.

## Checking for new content on demand (2026-08-01)

The six-hourly `NewContentWorker` was the least observable thing in the app: it runs in the
background, said nothing at all, and swallowed any exception into a bare `Result.retry()`. "I
never get notified about new episodes" could not be investigated without waiting a quarter of a
day per attempt.

**Settings → Check for new content now** runs it by hand and says what it found. Worker and
button call the same `NewContentCheck` — a button running *nearly* the same code is worse than
no button, because it would prove the wrong thing.

Its four outcomes exist because they mean different things to a person: nothing new is success,
undelivered almost always means notification permission was never granted (something you can
just fix once told), and a throw is a bug. They were a boolean and a discarded exception, which
is precisely how "I never get notified" and "there was nothing new" became indistinguishable.

Verified on device:

```
[podcast] refreshed 1 feed(s): 1 updated, 0 failed
[content] checked 2 source(s) (0 failed): 1 with items, 0 new across 0 source(s)
[content] nothing new; seen-state advanced
```

with "Nothing new since the last check." shown on the row itself.

## The pillar was lying, and the sync said nothing (2026-08-11)

Dewi asked whether every YouTube interaction reaches his account, so the algorithm learns from what
he actually listens to. It did not, and the diagnostics both proved it and explain why it took so
long to notice.

`PlaybackQueue.route()` handed the player `MediaKind.PODCAST` for its two audio routes — meaning
"play this as audio", not "this is a podcast" — and `WatchHistorySync` skips anything that is not a
video. So a YouTube video played from a downloaded file was never reported. Report 0.1.376: four
plays in three minutes, and only the streamed one reached YouTube. `playing.kind = PODCAST` for a
YouTube video is the whole diagnosis, sitting in the state block.

**Both early exits were silent `return`s.** That is why weeks passed: the app decided not to tell
YouTube about most of the listening, and no line anywhere said so. They now say which item and why,
once per item rather than per emission — the state stream ticks twice a second and a live stream
never learns its duration, which would be a flood.

Two further lessons worth keeping:

- **A session was opened in one place and needed in another.** `beginSession` was called only by
  the launcher's streaming path, so a queue item played from disk had none and every ping would have
  come back `NoSession` even after the pillar was fixed. It is now ensured where the reporting
  happens, which no caller can forget.
- **The scope of a claim needs checking against the code, not inferred from the shape of the bug.**
  My first account of this said Listen-mode streaming was affected too. It was not: that path goes
  through the launcher, which lets `kind` default to VIDEO. Writing the test is what found the
  correction, before it reached a commit message.

### Searches are attributed too, safely (2026-08-11)

Dewi: *"authed requests every if sensibly possible"*. Video search was anonymous, so none of it
reached his account — and search history is a real algorithm input.

A signed-in attempt now runs first, as the **TV client**, because that is the only identity
InnerTube serves a bearer token to (a bearer with an ANDROID or WEB context is answered `HTTP 400`,
the same lesson `playerDowngradedTv` already carries).

**It can only ever add.** An authed attempt that fails *or parses to nothing* is discarded and the
anonymous search runs instead. That second half is the important one: nobody can know from here
whether the TV client answers `/search` with renderers this parser understands, and "no results" is
not an error — so without that rule a wrong guess would silently empty the search screen, which
looks exactly like YouTube having nothing for you. Which path answered is **logged**, so the next
report from his phone settles it instead of another guess.

**It works, and that was measured rather than hoped.** The first version parsed only the classic
`videoRenderer` and fell back every time — seen on the emulator as `signed-in search parsed to
nothing`. Probing the endpoint with a real token showed the TV client answers with
`lockupViewModel` tiles instead, which **this repo already parses for the channel tabs**, so the
authed path reuses `LockupParser` rather than growing a third search parser. On device now:

```
[search] searched as the account — 8 result(s), so it counts towards history
[search] next page -> 8 returned, 16 total (more=true)
```

Paging is attributed too.

### One place attaches the token (2026-08-11)

Dewi: *"make sure we have as much as possible auth requests to YouTube. Maybe some global
middleware"*. Every InnerTube request already passes through one `execute`, so the rule lives there,
keyed on an `Identity` the call site names: **the token goes on every request whose declared client
will accept one, and on no others.**

Both halves are load-bearing. Forgetting it is a request that credits nobody — how watch history and
search stayed anonymous for months. Attaching it wrongly is worse: InnerTube cross-checks the
declared client against the headers and answers `HTTP 400`, so an over-eager middleware would break
playback rather than degrade politely. Seven tests pin both directions.

What still cannot be authenticated, stated rather than implied: **the streams client** (ANDROID
refuses a bearer — the TV player calls exist for the cases that need an account), **comments**,
**channel tabs**, and **music search**. `WEB_REMIX` is a web client, so YouTube Music's own
listening history remains untouched.

## Reading the report itself was the bug (0.1.383, 2026-08-14)

Dewi, having had the WarFronts diagnosis: *"any weird stuff in the diags i sent you??"*. Six
things, and three of them were the instrumentation lying rather than the app misbehaving. Worth
recording as a class, because every one of them reads perfectly in a **debug** build or in a
**short** session, which is why they survived.

### R8 renames the things we print

Every route line in the release report read `handle=wr3`. `PlaybackQueue` used
`javaClass.simpleName`, and R8 renames `PlayHandle.Video`. The field says which route an item
took — the exact thing that hid the watch-history bug three days earlier — and it was noise.
`PlayHandle.label` is now a literal per case, exhaustive so a new handle cannot be added without
one, and verified **in the minified APK** rather than assumed: `Video`, `LocalVideo`, `Podcast`
and `PodcastFile` are all present as strings in `classes.dex`.

The same fault, worse, in three more places: `wv1: Response code: 403` and `ob1: Source error` are
Media3's `InvalidResponseCodeException` and `ExoPlaybackException`. Those names reach `Diag`'s
error suffix, `playback.lastLoadError`, and the crash report's `exception` / `causeException` —
**and the crashlog server's web index groups by exception name.** An R8 name is stable only within
one build, so the same fault from two versions lands in two groups with names that mean nothing.
`-keepnames class * extends java.lang.Throwable` fixes all four at once; `mapping.txt` confirms
both classes now map to themselves.

### A per-item measurement that never resets is not a measurement

`PlaybackAnalytics` had no `onMediaItemTransition`, so `inFlight`, `loadedTo` and `outstanding`
accumulated for the whole session. Thirteen transitions in ninety-six seconds gave
`18 load(s) in flight, oldest 84804ms` — six of them the same `startedAt` values across four
different videos, because a load issued against a source the player has since released can never
complete, cancel or fail.

The costly one is `loaded to: track--1=3657572ms` against a **24-minute** video. `loadedTo` is
keyed by track name alone and only ever moves forward, so once anything reached 61 minutes the
figure was pinned at the session maximum. That is the number separating *starved* (nothing
buffered — network or URL) from *stuck* (data in hand, still frozen — decoder), and it was
answering "plenty buffered" unconditionally. Its own file already carried two comments about this
exact class of leak (0.1.306, 0.1.359) — both fixed for *cancellation*, neither for transitions.

`onLoadError` also decremented the count without publishing it, alone among the three terminal
outcomes — so the figure was stale-high in exactly the sessions it exists for.

### The rule

**A diagnostic is code, and it fails the same ways code does.** Ask of each one: does it survive
minification, does it reset when the thing it describes is replaced, and is it published on every
path that changes it? All three failures here answer "no" silently and produce a plausible number,
which is worse than producing none. `AnalyticsResetPerItemTest` (instrumented — `LoadEventInfo`
needs an `android.net.Uri`) and `PlayHandleLabelTest` pin them; both were watched failing first,
the analytics one reporting `audio=3657572ms` for a ten-second item.

## Saying what went wrong, in your own words (2026-08-15)

Dewi: *"it would be good to have a free-form text box where I can just tell you a bit more context
about the diagnostics, like the problem I faced in the UX"*. **Send diagnostics** now opens a box
first; what you type becomes the report's `note`.

The field always existed and always said `Sent by hand from Settings`, which the `kind` field
already implies — so every hand-sent report arrived with no statement of what it was about. That is
a bigger gap than it sounds, because a report is four hundred events and the reader's whole problem
is knowing **which moment to look at**. On 0.1.383 the sentence *"warfronts video not playing,
skipping to another Rest Is Politics video"* is what made `21:03:48` the interesting timestamp;
without it the trail reads as a video that failed and was skipped, which is the app working
correctly. Six words turned an ambiguous trail into a three-defect diagnosis.

Sending is never blocked on writing one — an empty box still sends, deliberately, because the worst
outcome is a report that never arrives because describing it felt like work. `diagnosticsNote` is
pure and holds the rules (trim, blank falls back, bounded at 2000 chars since the note is uploaded).

Three levels of test, because each covers a different way this can be broken: `DiagnosticsNoteTest`
for the rules, `DiagnosticsContentTest` for the note reaching the report JSON, and
`DiagnosticsNoteBoxTest` driving the real dialog — a box wired to nothing looks exactly like one
that works until the report that needed it, and that test was watched failing against a Send button
that ignored the typed text.

## A heartbeat that repeats itself is not a heartbeat (0.1.385, 2026-08-15)

The same report that confirmed the fixes above carried **52 byte-identical snapshot lines** — 28 of
`at 3033844ms (stopped)` and 24 of `at 1441250ms (stopped)` — because a paused player produces an
identical description every thirty seconds for as long as it stays paused. Half of that four-hundred
entry buffer (202 lines) was snapshot and memory heartbeats.

`ActivitySnapshotter` was already silent when nothing was *happening*. It is now also quiet when
nothing has *changed*, which is a different thing: a repeat is counted and the stretch stated once
when it ends — `...and unchanged for the next 12 snapshot(s), 360s`. Dropping repeats silently would
be the opposite mistake, since a player frozen at one position for twenty minutes is a finding and
would otherwise be indistinguishable from a gap in the trail. The duration is spelled out rather
than left as a bare count, because "×12" means nothing without the interval.
