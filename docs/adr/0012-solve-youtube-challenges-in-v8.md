---
title: YouTube's JS challenge is solved in Android's V8, with the player kept loaded
kind: adr
status: accepted
updated: 2026-10-06
---

# 12. YouTube's JS challenge is solved in Android's V8, with the player kept loaded

- Status: Accepted
- Date: 2026-10-06

## Context

After [ADR 11](0011-make-switching-to-video-fast.md), "Play once" took 4.6 s on Dewi's Pixel 7, and
the largest single step was yt-dlp's JS challenge solve. yt-dlp runs it in QuickJS as a new process
per solve, and each run parses a 3–4 MB script. Measured on the Pixel 7: 1.07 s with the preprocessed
player, 7.9 s for a new player build. A spike with `androidx.javascriptengine` (the phone's WebView V8,
in its own sandbox process) measured 525 ms to connect, 15–80 ms per solve with the player kept loaded
in the isolate, and 1.6–2.0 s for a new build. Its answers matched node's. Dewi: "yes crack on".

## Decision

1. **A yt-dlp challenge provider, `AndroidV8`, runs the solve in V8.** It subclasses yt-dlp's
   `EJSBaseJCP`, so it uses the same solver scripts and the same preprocessed-player disk cache. Its
   preference (900) is above QuickJS (850). It is registered only when Kotlin hands the bridge a
   runtime (`configure_v8_solver`). If V8 is missing or fails, yt-dlp's director falls back to QuickJS
   on its own.
2. **The isolate keeps the solver library and the two newest preprocessed players.** A solve for a
   kept build evaluates only the call. A new build is solved in full and its preprocessed player kept.
   The disk cache is still written, for QuickJS and for the next launch.
3. **Python passes the parts, not a script.** The plan was to split yt-dlp's generated script in
   Kotlin. Overriding `_real_bulk_solve` hands over the library, the player and the requests
   separately, so nothing depends on how yt-dlp formats that script.
4. **Warm-up preloads it.** The 10 s warm-up connects the sandbox, loads the library and keeps the two
   newest cached players, so the first tap after launch solves from memory.
5. **Any failure resets the isolate** (library and players forgotten) and is reported to yt-dlp as a
   provider error, so that solve falls back to QuickJS. A dead sandbox, for example after a WebView
   update, is dropped and reconnected on the next solve. On memory trim at `TRIM_MEMORY_BACKGROUND` or
   above, the isolate and the sandbox are closed.
6. **A WebView that cannot pass a multi-megabyte script** (`JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT`)
   turns V8 off for the session.

## Consequences

- ~30–60 MB more memory while the app runs (V8 holding the library and two players), released on
  memory trim.
- `androidx.javascriptengine` is added (~0.3 MB). V8 itself is the phone's WebView.
- yt-dlp's private `EJSBaseJCP` internals (`_lib_script`, `_core_script`, `_get_player`, the cache
  section) are now used. A self-updated yt-dlp that changes them makes registration or a solve fail,
  and QuickJS takes over. `totum_v8_provider_test.py` runs against real yt-dlp in CI.
- Diagnostics: `v8 sandbox connected in Nms`, `v8 kept|preprocessed|raw solve … in Nms`, `v8 isolate
  reset (…): reason`, vitals `v8.solves.*`, `v8.failures`, `v8.lastFailure` and `v8.state`. The
  `extract steps` line says `v8 on|off` and shows the solve as a `[jsc:AndroidV8]` step.
- Tests: `V8ChallengeRuntimeTest` runs the generated JS in node; `V8SolvesLikeNodeTest` solves a real
  player (fixture `player-1f293754.js.gz`) in the device's V8 and compares the answers with node's.
