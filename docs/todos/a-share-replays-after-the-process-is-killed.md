---
title: An old share replays after the process is killed
kind: todo
status: fixed 2026-09-23 — decided by how Android delivered the intent, not by a mark on it
area: share
priority: high
requested: 2026-09-23
updated: 2026-09-23
---

# An old share replays after the process is killed

Report 0.1.514 (`3370f14`, Pixel 7), note: *"why did Gill gross randomly start playing???"*. The app
cold-started at 13:21:14.435 and **48ms later** logged `shared link -> …2CDgKw6bpj4`. Nobody had
shared anything. After a 17s resolve, `play-now` inserted it (queue 150 → 151) and put it over the
news. It was a share from an earlier session: the video was already downloaded and resumed from
local progress.

## Why the 0.1.346 fix did not hold

0.1.346 had the same symptom (one TED talk, five times over five hours). Its fix marked the intent,
`intent.putExtra(EXTRA_SHARE_HANDLED, true)`, on the theory that the extra travels with the intent
the task redelivers. **It doesn't.** The extra lands on this process's copy. The task record in
system_server keeps its own copy, and that is what comes back once the process has been killed. So
the guard worked for same-process recreation and failed in exactly the case its comment described.

Reproduced on `totum-api35` with a debug build of `3370f14` (the 0.1.514 commit). Share a link, go
Home, and `kill -9` the process. The task survives, as it does under the low-memory killer, and
`dumpsys activity recents` shows it still holding `intent={act=android.intent.action.SEND …}`.
Then relaunch it the way Recents does: the same intent with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`
added (`flg=0x10100000`, the flags the system was seen adding on a real Recents tap). The share
played again. That relaunch was sent with `am start`, because this emulator's launcher didn't
show the dormant task as a card. The phone's report is the evidence that the real Recents path
does this.

## Fix

`shareArrival()` in `ShareArrival.kt` works out how the intent arrived, from what Android itself
reports:

- `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` gives `REOPENED_FROM_RECENTS`.
- `savedInstanceState != null` gives `RESTORED`. This covers a rebuild in either the same process
  or a new one.
- Anything else is `FRESH`, and only a fresh share plays. A new share while the activity is alive
  still arrives through `onNewIntent` as fresh.

A replay leaves a line saying so: `ignored a replayed share, nothing queued [<why>; via=… flags=0x…
restored=…] -> <url>`. Every accepted share carries the same facts, so the classification can be
re-judged from a report. The next report can then say which case happened, which
0.1.514's couldn't.

Tests: `SharedLinkTest` for the rule, and `ReplayedShareIsIgnoredTest`, which drives `MainActivity`
through a Recents-flagged launch, a recreate and a new share by `onNewIntent`. Only the Recents case
is red on behaviour against the 0.1.514 app. The other two fail there only because the log lines
they read didn't exist yet: the old in-process mark did cover a same-process recreate.

## Also changed, from review

- **The resolve runs on the app scope.** Tied to the activity, a rebuild cancelled it, and the
  rebuilt activity now reads the share as a replay, so the link would have been lost. The trade-off:
  a slow resolve (up to about a minute offline) can now start playing after the activity is gone.
- **A link with no video id is turned away before the resolve.** That meant `youTubeVideoId` had to
  read `v` as a query parameter wherever it sits (`watch?feature=shared&v=…`), or those links would
  have been thrown away.
- **The notification ask is switched off by a fresh share arriving through `onNewIntent`**, which is
  how a share lands when the process was dead but the activity's saved state was kept.

## Not changed, worth knowing

`handleAuthIntent` (`totum://auth`) has the same shape: it consumes with `setIntent(Intent())`
only, so a replayed task could re-apply an older home-server token. It hasn't been seen in a report.
It was left alone rather than folded in unasked.
