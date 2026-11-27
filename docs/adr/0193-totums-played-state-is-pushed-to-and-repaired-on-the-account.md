---
title: Totum's played state is pushed to the account, and repaired there when the account disagrees
kind: adr
status: accepted
updated: 2026-11-27
---

# 193. Totum's played state is pushed to the account, and repaired there when the account disagrees

- Status: Accepted
- Date: 2026-11-27

## Context

Dewi, 2026-11-27: *"I don't like any inconsistencies … play status, progress, finished should match YouTube on the
web."* Three reports (22, 23 and 27 November, v0.1.944) showed videos **Played** in Totum, after real listens of 9 to
26 minutes, while the account read the 10% floor ([progress-sync](../features/progress-sync.md): YouTube never reports
below 10, so a 10 means "started").

The investigator's evidence, checked against the code:

- On the normal path the final ping works. 28 natural ends across 23 reports sent `fin=true` with `final: Success`, and
  25 of them read 100% on the account within 1 to 5 minutes, including at 1.5x, 2x and with skip silence. So the
  failures are a minority class and their cause is **unproven**: the evidence was evicted from the report buffer
  (the `yt-sync` lines are in the chatty lane, 400 entries minimum, and one report held 31% of them as "Success").
- Four code paths could leave the account behind with no ping at all, and no retry ever repairs them:
  1. `AccountPlaybackProgressStore.setPlayed(true)` never told YouTube, so marking played by hand, in bulk, on a
     group, and a backup restore never reached the account.
  2. `WatchHistorySync.finishedVideoId` was never cleared, so the second completion of a video was not reported
     after another video had played in between (or after it was replayed from the start).
  3. A reset (`setPlayed(false)`) told the account nothing, and a rewind to under a second was swallowed by the
     "nothing watched since the last report" rule of `HttpYouTubeWatchHistory`, which answered `Success` without
     sending.
  4. A video YouTube refuses tracking for ("It was blocked due to the claimed content by …", `UNPLAYABLE`) was
     classed as a possible client fault, so it spent the sender's failure budget and was retried for ever (outbox
     row `gKo4mgMgOMw×6091` by 22 November, about every 15 minutes).

## Decision

1. **The account is told, and it can be seen being told.**
   - `Breadcrumbs.KEPT_TAGS` gains `yt-sync-item`: the per-item, per-decision lines that answer "did it reach the
     account", kept 12 hours and 1,000 entries rather than the chatty lane's 400. The routine `yt-sync` ping lines
     stay chatty on purpose.
   - Every final ping writes one kept line: `yt-sync-item <id>: pings=N lastCmt=<s> final=sent|failed
     session=fresh|reused ageMs=<session age> httpLast=<status>`. `ping()` now keeps the HTTP status of each request
     on its session. An item left without a final writes `final=none` with where it stopped.
   - Every account history read (and every `account-progress` read on a group or channel page) writes one
     read-back line for the items Totum holds as Played: how many read 100% on the account, and which read less,
     with the percentage. It is written only when its content changes.
2. **Totum's state is the truth for plays made in Totum, so a disagreement is repaired once, through the outbox.**
   `accountRepairFor` (`:core:domain`, pure) decides, for an item the account lists:
   - Played here and the account below 100%: re-send its final (`cmt=<length>&final=1`).
   - In progress here and ahead of the account by more than one percent of the duration (floor 60 s, the same
     granularity `resumeFrom` uses, now one shared function): re-send the position.
   - **It acts only when the account has not moved since Totum last acted on it** (`ReconciledAccountProgress` is
     null or equals the account's figure). A repair writes the figure it sent (the length for a final, the position
     for a position) as the acted-on figure, so it happens **once**: if the final does not take, nothing re-sends it
     on the next read, and if the account later holds a different, lower figure, that is another device's newer play
     and Totum leaves it alone. This is the existing direction rule for the other half: `ReconciledAccountProgress`
     still lets a newer account position from elsewhere win over an in-progress item (`resumeFrom` is unchanged), and
     a locally Played item stays Played (`accountAwarePlayState` is unchanged).
   - Skipped while an outbox row for the item is already waiting, when YouTube refuses reports for it, and under a
     test run's hold; at most 25 repairs per read, and the drain's own caps still bound the traffic.
3. **A hand mark reaches the account.** `AccountPlaybackProgressStore.setPlayed` is the one seam every hand path
   goes through (item menu, bulk select, group "mark played" and "start again", torrent file lists), so it enqueues
   a final for a YouTube item, using the length from the item's progress row, else the account's own duration for it.
   A mark on an item already Played changes nothing (the repair handles a stale account). Backup restore writes the
   raw store and is repaired on the next read instead of pinging hundreds of videos at once.
   - `setPlayed(false)` writes a ping at 0 only when the account already holds a position for the item: pinging an
     item YouTube never saw would put a new entry in the person's history. A rewind to the very start is now sent.
     **YouTube keeps a floor**: its history page reports 10% at the lowest, so a reset can leave an item reading as
     "started" there, and Totum cannot clear a history entry (that is a different call, not built). Unproven on the
     live account: the app's fakes cannot show what YouTube does with a ping at 0.
4. **A refusal YouTube means for good ends the row.** `PlaybackTrackingParser` reads `UNPLAYABLE` with a reason that
   says claimed content, copyright or private as about the one video (`Refusal.ThisVideo`), alongside `ERROR`, age
   verification and an offline live stream. These become `SessionResult.Refused`. A row that has been refused is tried
   on every pass until the limit, even one already on the slow stubborn path (the 6,000-attempt row would otherwise
   need about thirty passes to finish). After `GIVE_UP_REFUSED_AFTER` (3)
   refusals the drain deletes the row, remembers the video for the process, writes one kept line and lists it in the
   report state block as `yt-sync.refused`; `WatchHistorySync`, the repair and a hand mark stop queuing it. This
   reverses "nothing is ever dropped" for this one case only: that rule was measured against **no tracking in an
   otherwise normal answer** (three ids that later answered with tracking), which stays slow-retried and is never
   dropped. A genuinely stale signature timestamp (`The page needs to be reloaded`) is still read as possibly the
   client's fault.
5. **A skip that lands past the end ends the item once.** `SegmentSkipDecider` (`:core:domain`) clamps a skip target
   to the player's duration (an end-card segment from SponsorBlock ended at 333.341 s on a 333.333 s item), says
   when the clamp makes the skip end the item, and gives up on the same skip a third time within 10 s (logged once,
   segments then play out for that item). See [skip-silence](../features/skip-silence.md).
   Why the item repeated is only partly proven: the log shows about 20 repeats of "seek to the end, then a player
   transition with reason 0 (`REPEAT`) back onto the same item", each ending in buffering at 333332 ms, and the
   position ticker then finding the position still inside the segment. The clamp removes the seek past the end and
   the guard bounds any repeat that remains; the player's own reason for a repeat transition with repeat mode off
   is **not** established.

## Consequences

- A video finished in Totum but missed by the account is corrected within a read or two, once, and the report can
  show which items were corrected and which still read less.
- Marking played by hand, in bulk or on a group now costs a `/player` request and a ping per YouTube item, through
  the one polite gate and the drain's caps (20 rows a pass, at most three passes a kick); a very large bulk mark
  drains over several kicks.
- A web play made after Totum's last play of the same video is overwritten at most once, when the account has never
  been reconciled for it. This is the accepted cost of "Totum is the truth for plays made in Totum".
- An item hand-marked played that was never opened, has no progress row and is not on the account's lists has no
  length to send, so it is not reported (logged `no length known`).
- The cause of the minority of final pings that succeed on the wire but read below 100% is still unproven. The kept
  `yt-sync-item` lines (HTTP status, session age, whether the session was fresh) are what will settle it.
