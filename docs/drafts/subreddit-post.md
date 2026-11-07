---
title: Draft post for an AI subreddit
kind: reference
status: draft, not posted; for Dewi to edit and post himself
area: publishing
updated: 2026-11-07
---

# Draft post: "my favourite podcast app"

**Not posted. Nothing here has been sent anywhere.** Claude wrote it from the repo's own docs for Dewi to rewrite in his
voice and post himself. Lines in square brackets are his to fill or delete. Check the subreddit's rules on self-promotion
and on posting links before choosing where it goes; the rules differ by community and were not looked up.

## Title options

1. I built my favourite podcast app with Claude Code, and it now skips silences with a small neural net
2. One Android app for YouTube and podcasts, mostly written by an AI agent under my direction (GPL-3.0)
3. [your own]

## Body

I got tired of using two apps, one for YouTube (PipePipe) and one for podcasts (AntennaPod), so I made one app that
does both with a single queue. It is called Totum, it is for Android, and it is open source under GPL-3.0:
https://github.com/dewijones92/totum

[DEWI: one sentence in your own words on why you wanted it. Delete this line if you have nothing to say.]

It is built almost entirely with Claude Code. I describe what I want, the agent writes it, and the repo's rules (one
shared path for both kinds of media, tests first for a bug fix, a decision record for every real choice) are in
`CLAUDE.md` and `docs/adr/` so you can read how it is steered. [DEWI: confirm or change the wording of "almost
entirely"; it is your claim to make.]

What I think is interesting from an AI point of view:

- **Skip silence that runs a small neural network.** Instead of cutting anything under a volume threshold, it runs a
  speech detector (Silero VAD, ported to plain Kotlin so it runs on the phone with no extra runtime) so a pause in
  someone's sentence is not mistaken for a gap. A test pins the Kotlin to the original model's outputs.
- **A chat that finds things for you (✨ Ask).** Say "a podcast about the history of maps" and Claude Haiku, running on
  a small server at home, proposes a few titles. The app then finds each one with its own searches, ranks the copies,
  and asks you to confirm. It can also see what you follow and what you played most, so "less news, more history" works.
  It needs a home server; without one the app is a normal player.
- **Logs written for an agent to read.** Because I debug from reports sent from my phone, almost every decision the app
  makes is logged with the reason, and the repo has a flight recorder that saves the trail when playback stalls.
  [DEWI: if it is true for you, say here how it changed handing a bug report to an agent.]

The ordinary parts, for the record: one queue for episodes and videos, SponsorBlock, loudness boost, speed and a sleep
timer that fades out, downloads, casting to a Chromecast-style TV with the app doing the audio processing, a kid lock
that pins the phone to a bare video, and a screen-and-voice recorder for making demos like the one linked below.
[DEWI: add or cut to taste; do not claim anything you have not used.]

Honest limits: it started as an app for one person (me), the home server part is specific to my setup, and it is
not on the Play Store because it downloads YouTube streams. Releases are signed APKs on GitHub, or use Obtainium.
[DEWI: say whether you want bug reports, pull requests or neither.]

[DEWI: video link, if you make the video from `subreddit-video-script.md`.]

Happy to answer questions about how the agent workflow goes, including where it goes wrong.

## Before posting

- Open the repo as a stranger would: does `LICENSE` show GPL-3.0, are issues enabled, is the sponsor row hidden or set
  to the page you want?
- Remove anything from the video or screenshots that shows your account, your home server's address or a notification
  from another app.
- Do not show or name copyrighted films or torrent sites in the post or the video.

## Where each claim comes from

| Claim | Source in the repo |
|---|---|
| Replaces PipePipe and AntennaPod; one queue for both | `CLAUDE.md` (Totum section), `docs/spec.md` |
| GPL-3.0 | `LICENSE`, [ADR 146](../adr/0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md) |
| Silero VAD in plain Kotlin, pinned to ONNX Runtime's outputs | `CLAUDE.md` Decisions table (Voice detection), `SpeechModelTest` |
| ✨ Ask: Haiku on the Pi picks titles, the app finds and ranks the copies, you confirm; needs a home server | [ask.md](../features/ask.md), [ADR 109](../adr/0109-ask-haiku-picks-what-the-app-finds-and-ranks-which-copy.md) |
| Flight recorder | [crash-reporting.md](../features/crash-reporting.md), [ADR 120](../adr/0120-a-flight-recorder-and-reasons-for-every-pause.md) |
| Sleep timer that fades | [bedtime-sleep-timer.md](../features/bedtime-sleep-timer.md) |
| Casting with Totum's audio processing | [casting.md](../features/casting.md) |
| Kid lock | [ADR 76](../adr/0076-kid-lock-pins-the-app-to-a-bare-video.md) |
| Recorder | [totum-recorder.md](../features/totum-recorder.md) |
| Not on the Play Store; signed APKs on GitHub, Obtainium | `CLAUDE.md` (CI/CD row) |
| "Almost entirely with Claude Code" | not in the docs as a statement; the commit history carries Claude as co-author. Dewi's to confirm. |
