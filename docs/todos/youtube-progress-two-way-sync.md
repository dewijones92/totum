---
title: Is play progress two-way synced with YouTube?
kind: todo
status: shipped
area: video
priority: medium
requested: 2026-07-25
updated: 2026-08-16
---

# Two-way progress sync with YouTube

**Ask:** "confident that play progress is 2-way synced with YouTube servers or???"

**Short answer: no — it is one-way (app → YouTube), and even that is unverified
against YouTube's own UI.** Checked by reading the code, not from memory.

## What exists (outbound)

`WatchHistorySync` watches the single playback state and reports video progress to
YouTube — on a new video, roughly every 15s, and once when finished (within 15s of
the end). Podcasts are skipped, correctly. It pings the `videostatsWatchtime` /
`videostatsPlayback` URLs that `VideoPlaybackLauncher` registers via
`YouTubeWatchHistory.beginSession`; those URLs come out of yt-dlp's player response
(the bridge patches `_mark_watched` purely to *capture* them).

Confidence level, stated honestly:

- `HttpYouTubeWatchHistoryTest` proves we send the **right request shape** (against
  MockWebServer). It does not prove YouTube records anything.
- There's a `dewidebug` log of each ping's result, so a real device run can be
  inspected — but **I have not confirmed on youtube.com that History or
  cross-device resume actually updates.** That check is worth doing before claiming
  the feature works.

## What does not exist (inbound)

Nothing reads a position back from YouTube. Grep for `resumePosition` /
`startTimeSeconds` / resume-overlay parsing finds nothing outside this note. The
app's resume is entirely local (`PlaybackProgressStore`, Room), so:

- Watch 10 minutes on the TV, open Totum → it starts from 0 (or from *its own*
  saved position).
- Two devices both running Totum don't converge either; each has its own local
  store.

## What two-way would take

1. **Inbound position.** Two plausible sources, both already within reach:
   - the TV feed tiles carry `thumbnailOverlayResumePlaybackRenderer` (visible in
     the captured `feed_tv_sample.json`), which encodes how far through you are;
   - the watch-page/player response can carry a resume `startTimeSeconds`, readable
     at resolve time in `VideoResolver`.
2. **A reconciliation rule** — the actual design decision. When local and remote
   disagree: most-recent-wins (needs a timestamp on both sides, and YouTube's isn't
   exposed), furthest-through-wins (simple, but a deliberate re-watch gets dragged
   forward), or remote-wins-on-first-open-then-local. My instinct is
   **furthest-through-wins, but only when the remote is ahead by more than a
   threshold**, so small drift doesn't fight the local store.
3. **Verify outbound for real** first — no point building inbound on top of an
   unproven outbound path.

## Open questions for Dewi

- Is inbound resume actually wanted (continue on the phone what you started on the
  TV), or is one-way "my viewing shows up in YouTube history" enough?
- If inbound: which reconciliation rule feels right?
- Should podcasts get an equivalent? There is no server to sync to, so the honest
  answer is a local-only cross-device story (or nothing) — worth naming so the
  pillars aren't silently unequal.

**Done when:** outbound sync is confirmed against youtube.com, and (if wanted)
opening a video the account has partly watched elsewhere resumes at that point.
