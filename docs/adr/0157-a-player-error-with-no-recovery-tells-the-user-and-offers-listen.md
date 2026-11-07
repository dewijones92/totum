---
title: A player error with no recovery tells the user why, and offers Listen when the picture is the problem
kind: adr
status: accepted
updated: 2026-11-07
---

# 156. A player error with no recovery tells the user why, and offers Listen when the picture is the problem

- Status: Accepted
- Date: 2026-11-07
- Builds on: [ADR 70](0070-listen-and-watch-follow-the-picture-not-the-pillar.md) (Listen switches the video track off,
  for any source), [ADR 154](0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) (a Jellyfin film is the original
  file, so the phone must decode it)

## Context

On the emulator, tapping a Jellyfin film (a 10-bit HEVC MKV) failed with `ERROR_CODE_DECODING_FAILED (4003)` and
`format_supported=NO_EXCEEDS_CAPABILITIES`: the emulator has no 10-bit HEVC decoder (Dewi's Pixel 7 has). The app
showed a Play button that did nothing. `Media3PlaybackController.onPlayerError` only logged "player error with no
recovery for it": a stream failure that something can still fix goes to `StreamRecovery`, and every other error went
nowhere, for every source, not only Jellyfin.

## Decision

- **A hand-over that cannot be recovered is an event, like an end.** `PlaybackEvent.CouldNotPlay(itemId, atMs, problem,
  hasSound)` is emitted from the one place the player's errors are read, only for the errors `recoverableReason` says
  nothing can be done about. The log line says the decision and its inputs (error name, mime type and codecs,
  `hasSound`, position).
- **The problem is typed and named in plain words.** `PlaybackProblem.CannotDecode(track, format, errorName)` when a
  decoder error (`ERROR_CODE_DECOD*`) names a video or sound format, `Failed(errorName)` for anything else. The format
  comes from the exception's `rendererFormat` (the error crosses the media session as an `ExoPlaybackException`) and,
  failing that, from the `Format(...)` the exception's message prints. `CodecWords` turns it into what a person reads:
  "HEVC 10-bit" from `hvc1.2.4.L153.B0`, "H.264 10-bit", "AV1 10-bit", "VP9 10-bit", "E-AC-3", "DTS", and the bare
  mime subtype when nothing is known.
- **It reaches the user through the one way the app already says "couldn't play".** The queue turns the event into a
  `RefusedTap` (the toast `RefusedTapToasts` shows for a tap that could not play), now carrying the problem:
  "❌ Couldn't play “21 Jump Street”: this phone can't play this video (HEVC 10-bit)", "… this sound (E-AC-3)", or
  "… playback failed (ERROR_CODE_…)". No new toast, strip or dialog.
- **Listen is offered when it can help, through the app's snackbar-with-an-action.** When the picture was the
  problem, the item has a sound track, the app is not already listening and the picture was not already given up on, the
  tap carries the item, and the toast becomes a snackbar with "🎧 Listen only" (the same seam as ✨ Move up and ↩️ Undo).
  It runs `ItemActions.switchMode`, which is the row menu's Listen: the mode becomes sound and the item plays again with
  its video track off (ADR 70). Every source gets it, not only Jellyfin. A sound the phone cannot decode, a failure
  that is not a decode, and a film with no sound get the message and no offer.

## Consequences

- Nothing about recovery changes: a failure `StreamRecovery` can retry still retries silently. Only what used to end
  in a log line now ends in a message.
- A decoder error with no readable format is a plain "playback failed (ERROR_CODE_DECODING_FAILED)": naming a codec
  is only done from a format the error carries, never guessed.
- Listen cannot help when the sound is also undecodable (some MKVs carry E-AC-3 or DTS): the second failure is told as
  "this sound", with no offer.
- No transcoding (ADR 154 stands): a film the phone cannot decode can be heard, not watched, on that phone.
- Tests: `PlaybackProblemTest` (the emulator's own message, every decoder error name, the codec words),
  `AnUnplayableVideoSaysWhyTest` (the event reaches the user with the right offer or none, with the fake controller's
  `failToPlay`), `AnUnplayableVideoSaysWhyWordsTest` (instrumented, the wording).
