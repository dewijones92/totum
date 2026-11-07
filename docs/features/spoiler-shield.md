---
title: Spoiler shield
kind: feature
status: built 2026-11-07, Batch A (tests owed; not yet seen on a device)
area: ai/video
updated: 2026-11-07
---

# 🙈 Spoiler shield

For channels Dewi chooses (ATP, WTA, Tennis TV, any), Haiku rewrites each title without the result, thumbnails blur and
lengths hide until the video has been watched. The real title is one tap away. Decisions, the dry runs and what is not
covered: [ADR 141](../adr/0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md); the brief and the
tests still owed: [the todo](../todos/tennis-spoiler-shield.md).

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
  `LiveNoticeLabels`, `ProvideItemActions`, and `Media3PlaybackController.metadataFor` for the playback session.
- Reused: the Pi call client (`HomePiCall`), `AskOutcome`, the AI service's caller table, the row capability locals.

## Limits, honestly

See ADR 141: not covered are the seek bar's length, download notifications and Downloads rows that bypass
`MediaItemRow`, search-result rows built from a hit, the queue's "already queued" notices, the Cast receiver's own
title and the watch-page description the Details tab can fetch for itself. A multi-word surname is ordered by its first
word. A post-tournament video (a champions' dinner) tells you the tournament is over whatever its title says.

## Diagnostics

`dewidebug`-style lines under `spoiler`: each item's first sighting (its channel id and whether it is shown plain or
hidden, and why), every batch asked, every answer with what the model said, what the rules decided and why, failures and
the retry time, channel switches and reveals. On the Pi, `[spoiler]` lines with the same decision per title and the
`ai-task` job lines.
