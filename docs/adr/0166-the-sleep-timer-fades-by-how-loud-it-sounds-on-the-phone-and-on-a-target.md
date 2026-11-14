---
title: The sleep timer fades by how loud it sounds, on the phone and on a Pi target
kind: adr
status: accepted
updated: 2026-11-14
---

# 166. The sleep timer fades by how loud it sounds, on the phone and on a Pi target

- Status: Accepted
- Date: 2026-11-13

## Context

Dewi, for Marvin's nap stories (Peppa on a bedroom speaker): a gradual sleep-timer fade of about 20 s, steady, and it
must work when the story plays on a Pi target (ADR 152), not only on the phone. [ADR 133](0133-bedtime-arms-the-sleep-timer-and-every-sleep-timer-fades.md)
already fades every phone sleep timer, but with the ramp written inline in `SleepTimer`, linear in raw amplitude (so
the first half of a fade is barely heard and the last few seconds fall off a cliff), and a sleep timer chosen while a
target is selected had no way to reach the Pi: the phone's timer watched the phone's own, idle, player.

## Decision

- **One fade policy, in `:core:sound` (`SleepFade`, `FadeRamp`), used by both.** `SleepFade.gain(heardLevel)` turns a
  level on a straight "how loud it sounds" scale (time left over fade length) into an amplitude gain with the power law
  for loudness (heard loudness grows as amplitude to the 0.6), so half way through a fade the sound is half as loud,
  about 10 dB down, and the fade is steady to the ear. `FadeRamp` holds the steps: it asks for a change only when the heard
  level has moved by at least 1% (so a 20 s fade asked every 250 ms is about 80 even steps), never asks for a gain above 1
  (never above where it began), logs **once at the start** and **once at the end** (with the step count and a line when
  it reaches silence), and says whether there is a volume to put back. A fade length of 0 asks for nothing.
- **The phone's `SleepTimer` uses it** in place of its inline ramp, with the same output choices as before (the player's
  volume, or the TV's volume from where it was). A countdown shorter than the fade fades over its whole length instead
  of starting part-way down.
- **A target's sleep timer lives on the Pi's `TargetDeck`.** A new control `TargetAction.SLEEP` carries a
  `TargetSleepAsk` (`AFTER_TIME` with seconds, `AFTER_ITEMS` with the item ids and a title, or `OFF`, and the fade length
  in seconds), and `TargetState.sleep` reports what is armed (kind, time left, items left, whether it is fading). The
  deck's `DeckSleep` runs the countdown on the Pi so it survives the phone going to sleep: it ramps the target's volume
  through `TargetPlayer.volume` with `FadeRamp`, pauses at the end of a countdown (or stops, handing the target back, when
  the last item ends), waits a second for the pause to land, and puts the volume back to where it began.
  "After this item" and "at the end of the group" send the items' ids, the way `SleepTimer.stopAtEndOf` takes them; the
  deck refuses (409, the phone refreshes) when what plays is not among them. Moving off those items pauses, the phone's
  rule.
- **Never above where it began.** The fade drives volume through the same seam as the slider: a speaker's own volume
  (`TargetVolume`, [ADR 165](0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md), read again as the fade begins),
  else the player's (`TargetPlayer.fadeBasePercent`: the volume the phone last set, or mpv's own 100 when none was chosen).
  The base is that volume when the fade starts. A volume the person sets during a fade follows the
  fade down and becomes the new base only if it is lower; it never lifts the fade above the level it started at.
  Cancelling, stopping, losing the connection, replacing the timer and moving off all restore to that base. A target
  whose volume is not known (a Chromecast not yet read, a speaker whose own volume has not been read) is never faded from a guess, the line says so once, and it still pauses.
- **The setting is the existing one.** "Fade out" is the fade length on the Bedtime page, default 20 s, 0 turns it off;
  every place a timer is set (the quick strip, end of album, Bedtime's own arming, a target's timer) reads it. The
  phone sends it with each timer sent to a target.
- **Where a target's timer is set.** On the Queue tab, on a selected target's page, beside Repeat: the same sleep tile
  and menu as the player's quick strip (after this item, end of the group, 5 to 60 minutes). The player's own quick strip
  stays the phone's timer, because it is the phone's item playing. Bedtime's automatic arming stays on the phone.

## Consequences

- The phone's fade is now audibly different from ADR 133's: more of the drop is in the last seconds in raw volume,
  but even to the ear. `SleepTimerFadeTest` pins the new curve (a half-way fade is 0.315 of the volume).
- An old Pi receiving the new control answers 400 ("unreadable request"), which the phone shows as a trouble line; a
  new Pi's extra `sleep` field is ignored by an old phone. Deploy order: any.
- A target's timer is not saved with its queue: a Pi restart forgets it, and it is not in the Drive backup.
- Unverified on a real speaker, TV or Chromecast: that mpv's volume command and the helper's volume are audible as a
  smooth fade (a Chromecast steps in its own volume steps), and that the pause lands inside the one second wait.
- The countdown shown for a target moves with the phone's two-second read of the Pi, not each second.

## Amended 2026-11-14: the fade drives the control the volume mode names, and puts it back before the speaker is let go

An Opus review found four ways a target's fade could end louder than it began or leave the volume low. The "never above
where it began" rule above is now enforced by what is written, not just by the base number.

- **One control, the one the mode names.** `TargetVolume.beginFade()` returns a `VolumeFade` (the base and a `set` that
  never goes above where the fade began). For a speaker in `HARDWARE` mode it writes only the speaker's own volume, holding
  the raw value it began at; in `SOFTWARE` mode it writes only mpv's. Before, a fade in `SOFTWARE` mode with the speaker's
  own level also known (mpv at 40%, speaker at 10%, heard 4%) wrote the speaker's volume and put mpv back to 100%, so the
  first step made it about 40%. A fade write never moves between the two controls and never falls back to mpv when the
  speaker's own volume has gone.
- **The restore comes before the unlink.** `finish` released the speaker, then restored the volume, by which time the
  A2DP transport was gone and the restore fell back to mpv, splitting the two controls. It now stops the player, restores
  the volume, then hands the speaker back. A connection that is already lost (the transport is gone) cannot be put back:
  the write is skipped and logged ("the volume could not be put back … nothing else was made louder in its place").
- **A cancelled fade cannot write after the restore.** `DeckSleep.release` cancelled the timer's job without waiting, so a
  step still writing (a shell call is not cancellable) could land after the put-back and leave the volume low. Writes now
  go through one mutex per timer, and the restore takes it, closes the timer, then writes: an in-flight step finishes
  first, and a later one is dropped and logged.
- **A person's slider move during a fade, and a TV.** Unchanged: it becomes the new base only when lower. A TV or a player
  without a speaker volume fades through `TargetPlayer.volume` as before, never above its base.
- **Not under the deck's lock.** The slider, the tick's level read and the read after a link run outside the deck's lock
  (ADR 158), so a slow `busctl` cannot hold up Pause or Stop.
- **Tests:** `SpeakerVolumeTest` (the heard level, hardware × mpv, never above where it began after any write, including a
  speaker that never reports back), `TargetDeckVolumeSafetyTest` (restore before release, a lost speaker not put right
  through mpv, a step in flight when the timer is cancelled).
