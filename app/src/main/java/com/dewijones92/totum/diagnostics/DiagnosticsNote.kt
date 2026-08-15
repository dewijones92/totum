package com.dewijones92.totum.diagnostics

/**
 * What a hand-sent report's `note` field should say.
 *
 * Dewi, 2026-08-15: *"it would be good to have a free-form text box where I can just tell you a bit
 * more context about the diagnostics, like the problem I faced in the UX"*. The report has always
 * carried a `note`, but nothing could put anything interesting in it — every hand-sent report said
 * `Sent by hand from Settings`, which is the one thing the `kind` field already implies.
 *
 * It matters more than it looks. A report is 400 events, and the reader's whole problem is knowing
 * **which moment to look at**. On 0.1.383 the diagnosis needed the sentence "warfronts video not
 * playing, skipping to another Rest Is Politics video" to know that `21:03:48` was the interesting
 * timestamp out of four hundred; without it the trail reads as a video that failed and was skipped,
 * which is what the app is *supposed* to do. Six words of context turned an ambiguous trail into a
 * three-defect diagnosis.
 *
 * Pure so the rules are unit-testable without a screen: whitespace is not a note, and the field is
 * bounded because it lands in a JSON report that gets uploaded.
 */
public fun diagnosticsNote(typed: String, fallback: String): String =
    typed.trim().take(NOTE_MAX_CHARS).ifBlank { fallback }

/**
 * Generous enough for a paragraph describing what went wrong, bounded because a report is uploaded
 * over whatever connection the phone has and an unbounded field is an unbounded upload.
 */
internal const val NOTE_MAX_CHARS: Int = 2_000
