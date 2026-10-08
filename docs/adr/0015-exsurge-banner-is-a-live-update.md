---
title: The Exsurge banner is an Android 16 Live Update, so it shows on the lock screen
kind: adr
status: accepted
updated: 2026-10-08
---

# 15. The Exsurge banner is an Android 16 Live Update, so it shows on the lock screen

- Status: Accepted
- Date: 2026-10-07

## Context

Dewi asked for the Exsurge banner on the lock screen. The summons already takes over the lock screen
(full-screen intent), but the always-present banner ([ADR 5](0005-exsurge-alarms-and-the-sticky-banner.md))
sits on a low-importance channel, so Android treats it as a silent notification, and a Pixel hides
silent notifications from the lock screen by default.

The options were the phone's "show silent notifications" setting (no code, but it shows every app's
silent notifications), an Android 16 Live Update ("promoted ongoing" notification), or a widget.

## Decision

The banner asks to be a **Live Update** whatever the Exsurge state (Dewi chose "always" over "only while
active"), and is marked `VISIBILITY_PUBLIC` so its text is not redacted on the lock screen.

- Promotion is requested with `setRequestPromotedOngoing`, which exists only from **Android 16 QPR1
  (SDK 36.1)**; SDK 36.0 gets the chip text but no request. Below 36 nothing changes.
- The status-bar chip is decided once, by `bannerChipOf` in `:lib:exsurge`: a countdown to whatever the
  banner is waiting for (the summons, the end of a break, a snooze or a pause), else **GO!** (summoned),
  **7/20** (steps while rising), **Off**, or **Zzz** (asleep, or no summons left today). A countdown is
  `setWhen` + `setShowWhen`, so Android keeps it current between the banner's minute refreshes.
- *(Superseded by [ADR 23](0023-pinned-notifications-alert-silently.md): the banner moved to an alerting-but-silent channel so it shows on lock screens without Live Updates.)* The channel stays `IMPORTANCE_LOW` (no new channel): a promoted notification needs only a channel that
  is not `IMPORTANCE_MIN`, and the emulator showed the system promoting it on the existing channel.
- The banner's re-post on dismissal is kept, although Android's guidance says not to re-post a dismissed
  Live Update: the always-present banner is Dewi's explicit choice (ADR 5).
- The Exsurge permissions checklist gains a "Live Update" row on Android 16+, opening
  `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` when the user has switched promotion off.

## Consequences

- Each change in what was asked for is logged once (`dewidebug exsurge live update changed: requested=…
  promotable=… appAllowed=… chip=… sdk=…`), and a report carries `exsurge.liveUpdate.allowed`,
  `.lastPosted` and `.posted`, the last read back from the system's `FLAG_PROMOTED_ONGOING`. So a phone
  report says whether the banner was actually promoted, not only whether it asked.
- Verified on an Android 16 QPR1 emulator: the system set `PROMOTED_ONGOING` on the banner. The lock screen
  itself could not be screenshotted there (the WSL emulator's graphics crash System UI on that image), so
  how it looks is first seen on the phone.
- An OEM may add its own criteria; the diagnostics above are how that would show.
