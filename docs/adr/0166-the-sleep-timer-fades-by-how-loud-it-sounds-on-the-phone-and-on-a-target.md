---
title: The sleep timer fades by how loud it sounds, on the phone and on a Pi target
kind: adr
status: accepted
updated: 2026-11-13
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
- **Never above where it began.** The base is the volume when the fade starts (`TargetPlayer.fadeBasePercent`, the
  volume the phone last set, or mpv's own 100 when none was chosen). A volume the person sets during a fade follows the
  fade down and becomes the new base only if it is lower; it never lifts the fade above the level it started at.
  Cancelling, stopping, losing the connection, replacing the timer and moving off all restore to that base. A target
  whose volume is not known (a Chromecast not yet read) is not faded, the line says so once, and it still pauses.
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
