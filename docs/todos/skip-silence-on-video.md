---
title: Skip silence on videos too
kind: todo
status: shipped
area: playback
priority: medium
requested: 2026-07-24
updated: 2026-09-26
---

# Skip silence on videos (not just audio)

> **Superseded 2026-09-26.** Video now removes samples like audio. The desync that ruled that out
> was a wiring bug (see `skip-silence-smoothness.md`), and the remaining problem, the clock jumping
> when a cut is made rather than when it is heard, is fixed by `HeardSilenceAudioSink`. The
> speed-up described below has been deleted. See [`features/skip-silence.md`](../features/skip-silence.md).

**Ask:** skip-silence should work on videos as well, not only audio.

## Why it's currently audio-only (deliberate)

Skip-silence today uses Media3's `SilenceSkippingAudioProcessor`, which shortens
the **audio** stream but not the video/media clock — on a video that desyncs the
audio ahead of the picture (measured: audio ran ~6s ahead over a 20s clip). So it
was **forced off when a video track is present** and the UI hides the toggle for
video. See the `media3-silence-skip-desyncs-video` memory.

## What making it work on video needs (harder)

The audio-processor approach can't work for video. To skip silence on video you
must move the **whole timeline** past silent gaps, keeping A/V together:

1. **Detect** silent regions (timestamps). Options: a lightweight audio analysis
   pass, or reuse the processor purely as a silence *detector* (not a shortener)
   to find gap boundaries live.
2. **Seek** the player past each detected gap (both audio + video jump together,
   so they stay in sync) — enforced in one place, like the SponsorBlock
   `skipTargetFor` position ticker, so it's unified across pillars.

Risks: real-time detection latency/accuracy; seek jank; battery. May be better as
a "detect then seek" analog of the skip-segment mechanism than the audio
processor. Research spike first.

**Done when:** enabling skip-silence on a video skips silent gaps with audio and
video staying in sync (no desync), verified on-device.

## Spike 2026-07-25 — "detect then seek" is the wrong mechanism

Reusing the SponsorBlock path (feed silent spans in as `SkipSegment`s and let the
position ticker's `skipTargetFor` jump the timeline) is attractive because A/V stay
in sync by construction and it's already unified. **But it doesn't survive contact
with the frequency of the data:**

- SponsorBlock segments are *rare* — a handful per video. Silence gaps in speech are
  *frequent* — dozens per minute.
- Every skip is a **video seek**. A seek that isn't keyframe-aligned either snaps
  back to the previous keyframe (jumping backwards) or forces exact-seek decoding.
  Dozens of those per minute is visible stutter and a battery cost.

So the seek approach is rejected on mechanism, not on detection difficulty.

## Recommended instead: rate-based, and it *removes* a pillar split

Don't skip silence — **play it fast**. On entering a silent span set a high playback
speed, on leaving restore the user's speed. `setPlaybackSpeed` retimes audio *and*
video together, so:

- A/V cannot desync (unlike `SilenceSkippingAudioProcessor`, which drops samples the
  video clock never hears about — the bug that forced skip-silence to audio-only).
- No seeking at all: no keyframe problem, no stutter.
- Late detection degrades gracefully — you speed through the *remainder* of a gap
  rather than making a visible jump.

Detection: a custom pass-through `BaseAudioProcessor` used purely as a **detector**
(audio is decoded ahead of the playhead, so it reports a gap slightly before it is
heard) reporting spans to one controller in `:core:playback`.

**The win beyond the ask:** the same mechanism works for podcasts, so it replaces
today's audio-only `SilenceSkippingAudioProcessor` with **one seam for both
pillars** — which is what the Unified law wants, versus the current
audio-only/video-unsupported split.

**Approved (Dewi, 2026-07-25): one mechanism, both pillars.** Rate-based replaces
today's audio-only `SilenceSkippingAudioProcessor`. Accepted trade-off: podcasts will
*feel* different — a gap becomes a moment of fast audio rather than vanishing — in
exchange for video support, no possible A/V desync, and the pillar split disappearing.

## Shipped 2026-07-25 — one mechanism, both pillars

`SilenceDetectingAudioProcessor` sits in the sink's chain, passes audio through
**untouched**, and reports silence transitions. `PlaybackService` responds by raising
the **playback rate** (4× the user's chosen speed, capped at 8×) and dropping back when
sound returns. `SilenceSkippingAudioProcessor` is gone, and so is the audio-only gate:
the toggle now shows for video too.

Why this can't desync: `setPlaybackSpeed` retimes audio *and* video from one clock,
whereas the old processor removed audio samples the video clock never heard about
(measured ~6s of drift over 20s). And because nothing seeks, there's no keyframe
stutter — the reason the "feed silences in as skip-segments" idea was rejected.

**Why reacting immediately is accurate enough:** the detector runs inside the sink's
chain, and the sink consumes buffers in real time — the audio track holds only a few
hundred ms. "Just saw silence" therefore means "about to be heard", so the lead is
small relative to a gap worth skipping.

### Calibrated on-device (the part that needed a real device)

A first threshold of 128 (≈ -48 dBFS) **never triggered**: instrumenting showed real
peaks of 3,652–24,917 through a whole clip, because a recording's quiet passages sit
well above the theoretical noise floor. Raised to **1024** and ~150ms of quiet to
enter — both matching Media3's own `SilenceSkippingAudioProcessor` defaults, which are
battle-tested. That is exactly the "wired ≠ working" trap: the processor was correctly
installed and running (`configured enc=2 rate=44100 ch=2`, buffers flowing) and still
did nothing useful.

### Verified on-device

- **Podcast:** repeated transitions `speed 1.0 → 4.0 → 1.0`.
- **Video:** same, and with the user's speed at 1.25 the log read
  `speed=5.0 (user=1.25)` → `speed=1.25` — so it *multiplies* the chosen speed rather
  than overriding it, and restores the right rate afterwards.
- The toggle now appears for a video (it used to be hidden).
