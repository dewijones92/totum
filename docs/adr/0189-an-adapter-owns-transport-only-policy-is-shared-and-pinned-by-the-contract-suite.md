---
title: An adapter owns transport only; policy is shared and pinned by the contract suite
kind: adr
status: accepted
updated: 2026-11-28
---

# 189. An adapter owns transport only; policy is shared and pinned by the contract suite

- Status: Accepted
- Date: 2026-11-27

## Context

Casts to the LG through the Pi took 30 to 40 s. Report `20261120T211551`, from the Pi's own timestamps:

| Stage | Time |
|---|---|
| pychromecast connect | 6.1 s |
| before the first YouTube call | 5 s |
| player signature | 2 s |
| `solve_n` | 21.2 s |
| total | 37.6 s |

The Pi's logs since then (`docker logs -t totum-fetch`, 20 to 26 Nov) give every `solve_n` the Pi ran: 21.2, 11.3,
4.4, 7.3, 4.2, 4.1, 7.3, 5.7, 10.4, 11.6, 7.6, 6.6, 9.8 and 3.8 s (median 7.3 s). The per-start line says where the rest
went: 23 Nov 12:39 `link=6514ms resolve=7084ms total=13602ms`, so the TV connection and the resolve ran one after the
other; 21 Nov 10:41 `link=27203ms`, 23 Nov 07:51 `link=50793ms` (the LG waking from standby).

The Pi's engine was `ProcessYtDlpEngine`, which ran the same `totum_ytdlp.py` bridge as the phone but started a new
Python per call and whose `warmUp()` did nothing. The phone keeps one interpreter alive (Chaquopy) with the preprocessed
player cache on (ADR 11, ADR 12). So every Pi solve re-imported yt-dlp, rebuilt the extractor and the solver, re-read
the 4.4 MB preprocessed player from disk and started node on it; the first solve for a new player build (the 21.2 s,
which wrote `player,…6b3d0364…json` at 21:15:09) parsed the whole 3 MB script. Dewi's requirement (20 Nov): the two
engines must not drift again.

## Decision

1. **An engine adapter owns transport only.** How Python is reached (Chaquopy in-process, a long-lived subprocess) is
   the adapter's. What "warm" means, which caches are on, what is shared, and what is counted is policy and lives in
   the bridge (`totum_ytdlp.py`: `warm_up`, `_enable_solver_player_cache`, `_share_player_caches`, `prepare_player`,
   `engine_state`) and `:lib:ytdlp`. A new engine adds no policy; a change of policy is made once.
2. **One contract suite pins it** (`YtDlpEngineContract`, in `:lib:ytdlp`'s test fixtures). Every `YtDlpEngine` must
   pass: `warmUp` leaves the engine warm with the solver built, the preprocessed player cache on, the per-instance
   player caches shared and one HTTP pool; a warmed player is on disk and a solve for it neither fetches nor parses it;
   a second solve for the same player does neither either; a new engine over the same cache finds the warmed player
   without fetching it. The counts come from a hook in the bridge, so the suite asserts the mechanism and not a time.
   It runs against `ProcessYtDlpEngine` in JVM tests (skipped on a machine with no yt-dlp or node, required under
   `CI`), against the bridge directly in `totum_engine_policy_test.py` (run by preflight), and against
   `ChaquopyYtDlpEngine` as `ChaquopyYtDlpEngineContractTest` (instrumented).
3. **The Pi keeps warm workers.** `totum_ytdlp.py serve` reads one JSON request per line on stdin and writes one
   `{"id","output"}` line, `output` being the exact string the command prints, so no parser changed.
   `WorkerCommandRunner` runs two workers (solver commands; everything else), one request at a time each, so a long
   extraction never queues behind a solve and concurrency is bounded at two. A worker that dies, times out (120 s) or
   answers out of step is retired with a log line and restarted; a request that was never delivered is re-sent once.
   The timeout is one shared scheduled watchdog, cancelled when the answer arrives. `PythonWorkerTest` pins each of
   these against a stub script (a request answered by its own id; a worker that dies mid-request, hangs, answers for
   another request, or is killed between requests is replaced by a fresh one). Downloads still stream from their own
   process.
4. **The Pi warms itself.** `totum serve` calls `warmUp()` at start and warms the current player build
   (`PlayerWarmer`, which asks the signature-timestamp source for the build and calls `warmPlayer`, re-checking hourly),
   and builds the default resolver, so the first cast of a container's life is not the one that pays for them.
   The signature-timestamp source caches the build for the process's life, so a build that changes mid-run is warmed at
   the next restart, which is when the old code solved against it too.
5. **The phone hands over its resolve.** `PLAY` carries the phone's stored lookup for the item that starts
   (`TargetQueueRequest.handed`, the same JSON the phone's `FileLookupStore` keeps), only on an unmetered network, since
   the stream addresses are bound to the IP they were made for. The Pi probes one handed stream address with a 1 KB range
   read (3 s cap) and keeps the lookup only if it answers 2xx; a 403, a timeout or an unreadable lookup means it resolves
   for itself, as before. A kept lookup goes into the Pi's own `FileLookupStore`, so the resolver's ordinary
   stored-lookup path serves it and its ordinary `forget` on repeated failure drops it.
6. **The start position is carried.** `TargetQueueRequest.startAtMs` is now `Long?`, omitted from the JSON when null.
   Absent means "resume as usual" (the Pi uses the account's place from the player answer, never for live items or a
   jump); an explicit `0` means "from the start". `TargetsRepository.redirect` sends the place the phone itself would
   resume at (`randomStarts.peek` then the account-aware progress store). Send to… already sent one.
7. **The Pi's start is overlapped and measured.** The look-up of the item that starts begins as soon as the start does,
   beside the TV connection, and the start waits for both. One line per start gives every stage: time before the link,
   `link`, `sinkWait`, `resolve`, how long the start waited for the resolve after the link, `play`, `total`, and how
   far the resolve overlapped the link.

## Safe in any deploy order

| Phone | Pi | Result |
|---|---|---|
| old | new | the old phone always sends `startAtMs` (a number): explicit, as before; no `handed`: the Pi resolves for itself |
| new | old | `startAtMs` is omitted when null and `handed` is omitted when empty; an old Pi reads no `startAtMs` as 0 (the old behaviour) and ignores `handed` (`ignoreUnknownKeys`) |
| new | new | the phone's place and resolve are used |

The Pi's engine, warm-up and worker are internal to the Pi.

## The 5 s before the first YouTube call

Not fully explained, and said so. The log shows `[home] a resolver for the phone that asked ()` once per container life
(20 Nov 21:14:41) and then nothing for about 5 s until the first YouTube call. That line is written when the first
cast resolve builds its resolver, the Pi's JVM having never run that path, and a start whose resolver already existed
(23 Nov) shows 0.5 s there. The likely cost is first-use work on a Pi (class loading, building the resolver graph); the
resolver is now built at start and a `resolver was built in N ms` line says what it costs. Check that line on the next
restart before calling it closed.

## Timings, before and after (laptop, 28 Nov 2026)

Measured with the repo's own 3.0 MB player fixture served on loopback and a throwaway cache, through the same bridge
script the Pi runs: a throwaway script drove `solve_n` once per process (what `ProcessYtDlpEngine` did) and then through
`serve` (what it does now). The laptop was loaded by other work (load average 8), and it is several times faster than the
Pi, so these show the shape and the ratio, not the Pi's seconds.

| Step | Before: a process per solve | After: one warm worker |
|---|---|---|
| First solve for a new player build | 823 ms (start python, import yt-dlp, fetch, parse, preprocess, solve) | the parse (598 ms to `prepare_player`) is paid at start-up, then 173 ms |
| Second solve, same build | 444 ms | 178 ms |
| Third and fourth solves | 426, 465 ms | 182, 179 ms |
| A restarted worker over the same cache | n/a (every solve was a restart) | 326 ms to warm, then 240, 187, 179 ms |

So a warm solve is about 2.5 times faster than the old best case on the laptop and the first solve for a build no
longer sits in a cast. The Pi's 3.8 to 21 s `solve_n` was its old per-process cost on slower hardware; the number to
read after deploying is the `engine` line `worker solver: solve_n answered in N ms (start #S)` for casts after the first,
and the `warm` line `player …: ok=true … took=N ms` for the one-off parse at start-up (see "Measuring it on the Pi" in
`docs/todos/cast-start-speed.md`).

## What remains in a warm solve

On a laptop one warm solve is 255 ms: 25 ms to read the preprocessed player, 16 ms to build the node input, about
210 ms for node to start, parse the 4.4 MB player and run. yt-dlp's node provider starts a new node per solve, so this
stays. The phone's equivalent (a kept V8 isolate, ADR 12) has no Pi counterpart yet; a persistent node solver would
remove it, and is a separate decision.

## Consequences

- Policy cannot drift between the phone and the Pi without a contract test going red. The suite was seen failing
  against a runner that starts a process per call: 3 of 4 tests fail ("warmUp must leave the engine warm",
  "a new build is parsed once expected 1 but was 0", "the player must be held after warming").
- The Pi holds two Python processes (about 100 MB each) while idle, in exchange for no per-cast interpreter start.
- A cast to a target that turns out to be unreachable now makes one resolve it used to skip (the look-up runs beside the
  link). The resolve is kept for hours and reused by the retry; `TargetDeckStartTest` changed to say so.
- A worker is serial: two casts at once queue their solves.
- A handed resolve is a stream address from another device. The probe, the IP condition and `forget` on failure keep
  it from being worse than resolving, but a Pi that cannot reach the stream while the probe says it can is not covered.
- The Pi must be redeployed with this version before any of it applies; nothing is changed on it by the code alone.
