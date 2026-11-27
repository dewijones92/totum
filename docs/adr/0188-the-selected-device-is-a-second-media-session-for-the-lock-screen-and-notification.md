---
title: The selected device is a second media session beside the phone's, so the lock screen and the notification control it
kind: adr
status: accepted
updated: 2026-11-27
---

# 188. The selected device is a second media session beside the phone's, so the lock screen and the notification control it

- Status: Accepted (built and unit-tested; driven on the `totum-api35` emulator with a fake Pi; not driven against a real TV, soundbar or lock screen)
- Date: 2026-11-20, amended 2026-11-27 (one card at a time)
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
  are not touched, and a phone-only file playing on the phone while a device plays too keeps the phone's card (the one-card rule below).
- The cost, a second card when the phone also holds an item, is removed by the one-card rule below, so only one set of controls shows at a time.

The app's controller never sees the second session: `onGetSession` returns the phone's session unless the controller's connection hints
carry `RemoteSessionHost.HINT_REMOTE_SESSION` (only the instrumented test uses it).

### One card at a time (amended 2026-11-27)

The first version assumed a second session meant "two honest cards" when the phone also held an item. Dewi already complains about two
play controls (reports of 21 and 23 November, v0.1.944), so this was run on `totum-api35` (`RemoteTargetSessionTest`, the notification
manager's posted ids read back from `NotificationManager.activeNotifications`) before and after a change.

**Before (the build with no rule), same emulator, same fake Pi:**

| Phone's own item | Selected device | Posted | Verdict |
|---|---|---|---|
| none | playing | 1002 (the device) | right |
| playing | playing | 1001 (the phone) only | wrong: the chosen device has no card |
| playing | paused | 1001 only | right by luck |
| paused straight after loading (it never posted a card) | playing | 1002 only | right |

So the second session never produced two cards. Media3's notification manager (`MediaNotificationManager`) keeps ONE current
notification and one global update counter (fields `mediaNotification` and `totalNotificationCount`; a late update from one session is
dropped when another session has updated since). That reading of its fields fits the table but was not traced further; what was measured is that
only one of the two cards was ever posted and which one was not decided by anything we chose.
Which card won was an accident of timing: the same tie, both playing, gave the phone's card, and a phone that had not posted gave the device's.

**After:** `NotificationCards.choose` (`:core:playback`, pure) decides the one card, and `PlaybackService.onUpdateNotification` posts
only that session's notification, removes the other's by id (at 0, 1.5 and 5 s, each time re-checking that the card still loses, because
a foreground notification cannot be cancelled until the service has moved its foreground to the winner), and re-runs the decision
whenever the phone starts or stops playing, its playback state changes, or the device's session opens, changes or closes:

1. No device shown: the phone's card, exactly as before (the phone's notification, foreground hold and Exsurge are not touched).
2. A device shown and the phone holds nothing: the device's.
3. A device shown, the phone playing and the device not: the phone's (a phone-only file, Kid TV, the Shorts reel).
4. Anything else, including both playing and neither playing: the device's, because it is the one chosen.

Every move is logged once with its reason and inputs (`dewidebug the notification card moves from the PHONE to the DEVICE because …
[…; CardInputs(…)]`), and a removal logs the id it removed.

### One rule decides when the second session exists, and it is the dock bar's

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

## Alternatives

- **One session with its player swapped** (CLAUDE.md's usual preference, and the cast swap of ADR 156): rejected for the reason above, the
  app's controller mirrors that session and would treat the device's items as the phone's. It would also have shown one card by
  construction; the one-card rule gets that without teaching the controller.
- **Leave both sessions to Media3:** measured above, and wrong in the most common case.
- **Hide the phone's session from the system** (no notification for it while a device shows): rejected, ADR 14's foreground hold and the
  phone's controls for its own item must keep working; only its card steps aside, and comes back when the rule says so.

## Consequences

- With a device chosen, the lock screen and the shade show one card, its own in its colour (the phone's steps aside unless it is the only one playing), with title and art, and the four controls work
  without opening the app. Choosing the phone, or the device running out of items, removes the card.
- A tap on the card opens the app (its launcher); it does not yet open the device's remote page.
- The card's position and state are at most a read old (10 s playing, 30 s paused, with the screen off), and a tap is shown at once.
- **At Dewi's scale** (5,857 queue items): the session holds at most three items (previous, current, next), never the Pi's queue, so
  nothing here grows with it.
- A phone item paused behind a shown device has no card until the device goes (its controls are in the app); a playing phone item
  reclaims the shade and the device's card comes back when it stops.
- Not run on a real TV or soundbar, nor on a real lock screen: the emulator shows the notification shade, not its lock-screen
  presentation.
