---
title: Soaking a playback or extraction change against the build before
kind: reference
updated: 2026-11-28
---

# Soak procedure

The rule itself is in `CLAUDE.md` ("Before shipping a playback or extraction change"), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

**Read all of it before running a soak.**
The two schema-bump paragraphs that sat in the middle of it are in [`room-schema.md`](room-schema.md).

### Before shipping a playback or extraction change: soak it against the build before

Dewi, 2026-10-07: *"are you testing thoroughly enough on the emulator before u ship? maybe not?"* — no.
Two regressions reached his phone that day (every lookup stuck at 360p; playback frozen after a long
pause) because each change was checked with one or two taps that proved its mechanism fired, never a
session, never the build before it for comparison, and never on an emulator set up like his phone.

**When the soak runs (Dewi, 2026-10-23):** each feature is pushed as soon as its gate is green and it has been checked
on the emulator, so Dewi can try it on his phone; the soak below runs **once at the end of a batch**, against the
release before the batch, in parallel with the Opus review (see "How a batch runs"). A regression the soak finds is
fixed with a test seen red first and pushed straight away. This replaces soaking before every playback push.

The soak itself, for a batch that touched extraction, resolving, playback, the queue or downloads:

1. **Build the previous build too**, without touching the working copy:
   `git archive <ref> | tar -x -C <scratch>/baseline && cp local.properties <scratch>/baseline/ && (cd
   <scratch>/baseline && ./gradlew -q :app:assembleDebug)`. Both are debug-signed, so `adb install -r`
   swaps them and keeps the sign-in.
2. **Soak both** on `totum-api35`: `ANDROID_SERIAL=emulator-5554 tools/emulator/soak.py run --label <x>
   --out <scratch>/soak/<x>.json --start-budget-ms 12000` (three passes by default, ~15 minutes). It plays
   the videos from past reports as shared
   links (the worst case for the fresh-URL window), clears saved lookups first so the runs are comparable,
   and records from the app's own `dewidebug` lines: time to sound, quality, video decoded, 403s, fatal
   403s, stalls, underruns, the 360p/TV fallback, and falling back to the disk.
3. **`tools/emulator/soak.py compare before.json after.json`** — failing on more passes, a median start
   1.5× + 2 s slower, or a lower quality is a regression and blocks the push. Put the table in the commit
   message. One pass is NOT enough: the same video by the same route started in 0.8 s and 2.6 s on two runs,
   and stalls and underruns come and go on the emulator in both builds. The first soak (one pass each) called
   six regressions, four of them noise; it also caught a real one, an all-clients retry firing on 6 of 10
   videos that already had 1080p.

**A change to how the queue hands over gets a queue soak too:** `ANDROID_SERIAL=emulator-5554
tools/emulator/soak.py queue --label <x> --out <scratch>/queue.json` builds a real YouTube queue through a
debug-only receiver (`QueueDebugReceiver`), seeks each item to 40 s before its end, and records every
hand-over (crossed over or not, silence, stalls, adopted) with gapless off and then on. 2026-10-07: off 4.6 /
3.7 / 4.4 s, on 0 / 0 / 0 s. It caught a race no device test reproduced (an item under 45 s never put its
successor in line, because the queue's adoption cancelled the arming); local files win that race, streams lose it.

**Name the baseline by its commit, and expect a wide noise band (2026-10-14):** a baseline directory reused from an earlier day was v0.1.604, not the 0.1.605 it was reported as, so a "605 vs 606" soak measured three changes. Build the baseline fresh from the previous release's tag (`gh release list`, then `git archive v0.1.<n>`) into a directory named after it. The same build failed 3/30 and 8/30 an hour apart (all underruns and `edon5wb5Qsc` give-ups), and emulator CPU did not separate failed plays from passing ones (idle ~100% vs ~110% of 400%, app ~140% either way), so a gap of a few failures between builds is noise until a third run repeats it.

**Skip finished confounds the queue soak (2026-10-22):** a baseline run plays its items to the end, so the next build passes over all of them (ADR 74) and reports "no hand-over seen"; the debug play-all now marks its items unplayed first. Read the `[queue] … passed over N finished` line before calling a queue soak a regression.

**Queue auto-download confounds a soak (2026-10-14):** each soak play leaves its video in the queue, so later runs inherit more undownloaded items and the queue auto-download fetches them during the plays (0 and 1 downloads in the two v0.1.606 runs, 3 and 5 in the two later runs of the new build, whose failures were 7 and 7 against 3 and 4). Check the header's other services for `DownloadKeepAliveService`, and clear the queue or switch queue auto-download off before every run.

**A soak no longer reports its plays to Dewi's YouTube account (2026-10-28):** the emulator is signed in to his real
account, so every soak play used to land in his YouTube history and push his own videos off the page the app reads.
`soak.py run` and `soak.py queue` hold reports for their whole run (a prefs file, removed at the end); a run killed
half-way leaves the hold on, so delete `shared_prefs/totum_test_holds.xml` with `run-as` if one dies. Writing the hold force-stops the app, and from 2026-10-28 to 2026-11-04 nothing relaunched it before `queue`'s
broadcasts, so every queue soak read "never started" on every hand-over; the hold now relaunches the app.

Keep the laptop idle while a soak runs (2026-10-12): a Gradle build or test run during a pass steals the emulator's CPU and makes that build's starts and underruns look worse, so build and test before or after, never during.

Two traps in the tool itself (2026-10-09): `soak.py run` **exits 1 when any play fails**, so chain the
queue soak after it with `;`, not `&&` (an `&&` silently skipped the baseline's queue soak); and the queue
soak drives the app through a broadcast, which a **force-stopped app never receives** (every hand-over
reads "never started"), so launch the app first. `compare` reads only `run` reports; compare queue
reports by hand, and re-run a queue soak before believing one lost adoption: a run with another
foreground service left over from instrumented tests adopted 2/3, the clean rerun 3/3.

**Switch Exsurge (and Daily alarms) off on the emulator before a soak, for both builds.** On 2026-10-07
the third of three soaks ran with Exsurge off and the first two with it on; the third looked far better
(30/30 started, 18 stalls vs 45-50) and none of that could be credited to the change. A summons can also
pause a soak video outright.

Keep the emulator honest: its header line lists the app's other running services, and Exsurge's banner
service masks foreground-service bugs (it hid ADR 14's freeze for a week). YouTube's experiments vary by
visitor and by day, so a soak cannot prove the phone will behave; it catches what a session on the same
network would. One tap proves a mechanism; it never proves the app.
