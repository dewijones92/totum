---
title: Spoiler shield
kind: feature
status: built 2026-11-07, Batch A (JVM tests written 2026-11-07; not yet seen on a device)
area: ai/video
updated: 2026-11-14
---

# 🙈 Spoiler shield

For channels Dewi chooses (ATP, WTA, Tennis TV, any), Haiku rewrites each title without the result, thumbnails blur and
lengths hide until the video has been watched. The real title is one tap away. Decisions, the dry runs and what is not
covered: [ADR 141](../adr/0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md); the brief and the
tests and what is still owed: [the todo](../todos/tennis-spoiler-shield.md).

## What it does

1. On a channel's page, the 🙈 icon in the header switches the shield on for that channel (the same switch is "Hide
   spoilers from this channel" in any video's action sheet). Nothing is shielded until you do.
2. Every list that shows one of its videos now shows "🙈 Title hidden" for a moment, then the safe title: names in
   alphabetical order joined by "v", the tournament and round kept, every hint at the result removed. The picture is
   blurred and the length is gone.
3. The playing video's mini player, full player, notification and lock screen say the safe title, with no description
   and no picture; the Next line, Share, Copy link, Share list and the new-video notifications say it too.
4. **Show the real title** (action sheet) lifts it for that video; **Hide the title again** puts it back. A video you have
   played shows its real title by itself.

## Seam

- `core/domain/ItemShield.kt`: `ItemShield.face(item)`, `ItemFace`, `NoShield`, `titleOf`.
- `core/data/spoiler/`: `SpoilerShield` (state, the check queue, persistence in `spoiler-shield.json`), `SpoilerRules`
  (the result-word rules and `decide`), the request and answer types.
- `core/data/ai/AiJobs.kt`: `HttpAiJobs` (the phone's client for every AI task) and the task names.
- Pi: `cli/.../serve/tasks/` (`AiTaskRoutes`, `AiTaskRunner`, `SpoilerPrompts`, `HomeAiTasks`); route
  `POST /ai/spoiler`, caller `spoiler`, no tools.
- App: `ai/AiFeatures.kt` (built in `AppContainer.aiFeatures`), `ui/common/SpoilerShieldUi.kt` (the composition locals,
  the sheet actions, the channel toggle), `MediaItemRow`, `CarryOn`, `ItemDetails`, `PlayAttempts`, `NextLine`,
  `LiveNoticeLabels`, `ProvideItemActions`, and `Media3PlaybackController.metadataFor` for the playback session (which
  also carries the real title and a shielded flag, `PlaybackState.realTitle` and `.shielded`, for the stats and the
  transcript tab). `core/domain/ItemShield.kt` also holds `ItemShields`, the process-wide shield for code with no
  composition. `ai/AppTaste.kt` is the one taste and owned-titles source for Ask and the morning job.
- Reused: the Pi call client (`HomePiCall`), `AskOutcome`, the AI service's caller table, the row capability locals.

## Limits, honestly

The policy and the full lists are in ADR 141: every surface that shows or sends a title reads the shield (composables
through `LocalItemShield`; notifications, toasts and shortcuts through `ItemShields`), the logs and the stats records
keep the real title, and the stats screen, the Ask pack and the taste go through the shield. For an item the shield is
hiding, the transcript tab says "🙈 Hidden until watched" until you reveal it. Not covered: the seek bar's length, the
Cast receiver's own title and the watch-page description the Details tab can fetch for itself; podcast show hits and
torrent results are never shielded (the shield is per YouTube channel). A multi-word surname is ordered by its first
word. A post-tournament video (a champions' dinner) tells you the tournament is over whatever its title says.

## Diagnostics

`dewidebug`-style lines under `spoiler`, counted not per row (2026-11-14: 989 of 1,400 events in a report were
"shown plain" lines, 542 in one second at start-up, and `spoiler` is a kept tag, so they also crowded out every other
kept tag): one `faces:` line per 2 s window with the count of new items by outcome (shown plain and why, hidden,
safe title) and the first three as examples, every batch asked (first three ids), one `verdicts:` line per answer
(kept, rewritten, replaced by the neutral title, no verdict), a line of its own for each title where the rules
overrode the model, failures and the retry time, channel switches and reveals. On the Pi, `[spoiler]` lines with the same decision per title and the
`ai-task` job lines.
