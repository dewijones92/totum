---
title: The first visit to Music and Queue costs one long frame
kind: todo
area: performance
priority: medium
status: found 2026-10-15; measuring attempted 2026-11-14, blocked (emulator died); nothing changed
updated: 2026-11-14
---

# The first visit to Music and Queue costs one long frame

## Found

Tracing tab switches on the emulator (debug build, `totum-api35`, a 300-item queue playing; the two-round
tap script from the ADR 61 sweep), v0.1.635 against 5a4a3e7a: frames over 50 ms went 33 → 8 and main-thread
time 8.7 s → 5.9 s, but the worst frame stayed at about 400–580 ms in both builds. It is the **first** visit
to a tab: Music took one 581 ms frame (401 ms in `animation`, which is composition), Queue one 568 ms frame
(389 ms in `traversal`, measure and layout of the list). The second visit to each costs about 100 ms.

## Not yet known

- How much of it a release build removes. Debug builds are unoptimised and JIT-only; the release APK carries
  the libraries' baseline profiles through profileinstaller (`assets/dexopt/baseline.prof`), but none for the
  app's own code.
- What it costs on the Pixel 4a, which is the device that matters.

## Options

- An app **baseline profile** (a `:baselineprofile` module driving startup and each tab through
  Macrobenchmark), so the app's own composition code is compiled ahead of time on install.
- Compose the first page of each tab lazily, or keep a visited tab's composition alive.

## 2026-11-14: not measured, and why

The 2026-11-14 pass was to load Dewi's real state dump (queue 5,857, 1,623 subscriptions, 509 downloads) with
`tools/emulator/load-state.sh` and read the `[main]` stall lines (ADR 100) and a Perfetto trace for the first visit to each tab. It did not
happen: the project emulator process died with a segfault (signal 11, `qemu-system-x86_64-headless`, 06:55)
during the builder's fourth Exsurge test run on it (not shown to be caused by the test), and the builder's brief forbids booting one. The dump holds no `[main]` lines and no
vitals (its `events` are 1,400 log lines, none tagged `main`), so there is no phone evidence to use instead.

Nothing was changed on a guess. What a measurement should run, so it is quick next time: load the dump, force-stop,
launch, then for each tab (Music, Queue, Library, Podcasts, Videos) tap it once and read `adb logcat -d -s dewidebug`
for `[main] stall` lines and `main.worstStall` from a diagnostics report; compare with the same tap a second time. Debug
build first for the shape, release (`-PemulatorAbis`) for what Dewi feels.
