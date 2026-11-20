---
title: The selected device is a second media session beside the phone's, so the lock screen and the notification control it
kind: adr
status: accepted
updated: 2026-11-20
---

# 188. The selected device is a second media session beside the phone's, so the lock screen and the notification control it

- Status: Accepted (built and unit-tested; driven on the `totum-api35` emulator with a fake Pi; not driven against a real TV, soundbar or lock screen)
- Date: 2026-11-20
- Amends: [ADR 180](0180-the-selected-device-owns-the-dock-bar-and-the-remote-page-is-a-card-over-its-list.md) ("Lock-screen and
  notification controls are not built")

## Context

[ADR 180](0180-the-selected-device-owns-the-dock-bar-and-the-remote-page-is-a-card-over-its-list.md) put the selected Pi device (speaker,
TV, Chromecast, Kodi, browser) in the dock bar of every tab and left the lock screen and the notification shade for later. Dewi's
"remote bar first, lock screen later" is now: while a device is chosen and has something to show, he can play, pause, go back 10, forward
30 and to the next item from the lock screen and the shade, and see the title, art and device name.

The app has one `MediaSessionService` (`PlaybackService`) with one session on the phone's player. The brief's first idea was to swap that
session's player for one fronting the device (a `SimpleBasePlayer` whose state is the device's `TargetState` and whose commands become
`TargetAction`s), and back again. ADR 156's cast swap (`switchTo`) is the precedent.

## Decision

### A second session in the same service, not a swap

`PlaybackService` keeps its one phone session, and a `RemoteSessionController` opens a **second `MediaSession` in the same service**
(`addSession`, id `totum-remote-target`) while a device is shown, and closes it when it is not. A `RemoteTargetPlayer`
(`SimpleBasePlayer`) fronts it. Why not a swap:

- **The app's own `MediaController` is connected to the one session** and mirrors whatever its player says (`Media3PlaybackController`,
  84 KB of it): it saves progress for the current item, marks an item played at `STATE_ENDED` and advances the phone's queue from it,
  feeds history and stats, and reads the item's id to decide what is playing. The cast swap is safe because it hands the SAME item across;
  a device plays a different item with a different position, and a swapped-in device that reached the end of an item would make the
  phone's queue play the next item on the phone while the TV plays on. The swap would have needed that controller taught that it is
  looking at somebody else's player.
- **It makes "the phone keeps working exactly as before" true by construction:** the phone's player, session, notification (id 1001),
  Exsurge's Live Update ([ADR 15](0015-exsurge-banner-is-a-live-update.md)) and the foreground rules ([ADR 14](0014-playback-service-stays-foreground-after-a-pause.md))
  are not touched, and a phone-only file playing on the phone while a device plays too is two honest cards instead of a lie.
- The cost is a possible second card when the phone also holds an item (paused, say). That is two real things, each controlling its own.

The app's controller never sees the second session: `onGetSession` returns the phone's session unless the controller's connection hints
carry `RemoteSessionHost.HINT_REMOTE_SESSION` (only the instrumented test uses it).

### One rule decides when it exists, and it is the dock bar's

`barDecision` (`:core:data`, the rule `barFor` already was, now with the reason) decides for the dock and for the lock screen: the second
session exists exactly when the dock bar is the device's (the chosen device, listed, with a current entry). An idle, unlisted or
unchosen device, or the phone being the choice, means no second session and no second notification. Every change is logged once with its
reason (`RemoteSessionPlan`: started, moved between devices, moved on to another item, updated, ended).

### The state and the commands

- **State:** `TargetState.toRemoteFacts` (`:app`) gives title, author, art (all through the spoiler shield's `face()`, so a shielded item
  shows "🙈 Title hidden" and no art, as on every other surface), the device's label, colour and status line (the dock bar's own words,
  `statusRes()`), position, length, playing and starting. `RemoteView.of` maps that to a player view: a playlist of the previous, current
  and next item (so next and previous are real player commands), `STATE_BUFFERING` while the device starts, `STATE_READY` otherwise,
  `playWhenReady` for playing or starting, no length for live (no back or forward then). The position is extrapolated between reads
  (`PositionSupplier.getExtrapolating`, created once per read), so the lock screen's bar moves.
- **Commands:** play and pause, seek (back 10 and forward 30 arrive as `seekBack` and `seekForward` with the increments from
  `SeekSteps`), next and previous become `RemoteCommand`s, then the same `TargetAction`s the dock bar sends through
  `TargetsRepository.act`, so the answer replaces the polled state at once. A seek is kept inside the item. The player holds the shown
  state until the device answers or 2.5 s, so a tap does not snap back for the length of a round trip.
- **Name and colour:** the artist line is `📺 LG TV · ▶️ Playing · The Rest Is Football` (the lock screen shows title and artist); the
  notification carries the device's name as its sub-text and its colour as the notification colour (`RemoteTintedNotificationProvider`
  re-tints Media3's notification, including its late artwork update). Android draws the colour only where it draws one.
- **Own notification id (1002)** beside the phone's 1001, and the phone's context lines ("Next: …") are not added to the device's.
- **Buttons:** back 10 and forward 30 in the back and forward slots, next in the secondary forward slot, play and pause central.

### Reading the device while the screen is off

The dock bar reads the Pi every 3 s while a device is chosen, but only while the app is in the foreground. For the lock screen the
bridge (`RemoteSessionBridge`) reads **every 10 s while the device plays or starts and every 30 s while it is paused, and only if no
other read happened in the last 80% of that time** (`TargetsRepository.refreshIfOlderThan`, and a command's answer counts as a read), so
with the screen on it adds no request and with it off it is never faster than the dock bar and slower than its 3 s. It stops when
nothing is shown. The service goes to the foreground and stays as [ADR 14](0014-playback-service-stays-foreground-after-a-pause.md)
says, through the same `ForegroundHold`, fed by the device's player as well as the phone's.

## Consequences

- With a device chosen and playing, the lock screen and the shade show it in its colour, with title and art, and the four controls work
  without opening the app. Choosing the phone, or the device running out of items, removes the card.
- A tap on the card opens the app (its launcher); it does not yet open the device's remote page.
- The card's position and state are at most a read old (10 s playing, 30 s paused, with the screen off), and a tap is shown at once.
- **At Dewi's scale** (5,857 queue items): the session holds at most three items (previous, current, next), never the Pi's queue, so
  nothing here grows with it.
- Not run on a real TV or soundbar, nor on a real lock screen: the emulator shows the notification shade, not its lock-screen
  presentation.
