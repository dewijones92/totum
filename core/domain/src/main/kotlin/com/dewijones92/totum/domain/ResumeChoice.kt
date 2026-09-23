package com.dewijones92.totum.domain

/**
 * Where to resume an item, given what this device remembers and what YouTube remembers.
 *
 * Dewi asked for two-way progress sync (2026-07-25, picked up 2026-08-16). The outbound half
 * already worked and is now **measured** rather than assumed: the app reported `caVJh4jrOxE` at
 * 789.873s, and YouTube's history came back holding 13% of a 1:44:13 video — 789/6253 = 12.6%.
 *
 * That measurement is also the constraint this rule exists for. **YouTube gives back a whole-number
 * PERCENT**, not a position: `percentDurationWatched` on the history tile's resume overlay. On that
 * 1:44:13 video one percent is 62 seconds, so a remote position is only ever accurate to about half
 * a minute either way, while the local one is exact to the millisecond.
 *
 * So the rule is **local wins unless the remote is meaningfully ahead**. Blindly preferring YouTube
 * would make resume *worse* on the device you actually watch on — reopening an item you paused
 * thirty seconds ago and being thrown half a minute off. Preferring local always would defeat the
 * point: watch forty minutes on the TV and the phone still starts from nothing.
 *
 * "Meaningfully" is one percent of the duration, floored at [MIN_GAP_MS], because one percent is
 * exactly the resolution of the number being compared — anything smaller is noise, not knowledge.
 *
 * **And "ahead" has to mean NEW.** Report 0.1.496 (2026-09-20), Dewi: *"I have tried to rewind the
 * video back to the start but it is not working"*. He rewound `vceHVwxOnhA` six times and every
 * re-entry answered `REMOTE_IS_AHEAD [local=11273 youtube=77700]` — the same 77700 each time,
 * because the outbound half was refused (`held=123`) so the account's number could not move. A
 * remote position this device has already acted on ([remoteAlreadyUsedMs], from
 * [ReconciledAccountProgress]) is not knowledge about what has happened since; it is the echo of
 * the last decision, and it must not overrule a deliberate rewind for ever. A number that has
 * actually MOVED still wins, which is the forty-minutes-on-the-TV case intact.
 *
 * **And 10% is not a position at all.** Report 0.1.514 (2026-09-23), Dewi: *"why downloaded files
 * have position of like 5% in to the video?"*. Five videos played for a few seconds each came back
 * from YouTube at exactly 10%, and so did 12 of the 17 remote positions across every report so far,
 * none of them lower. YouTube floors the percentage at 10, so a 10 says only "started". The 77700ms
 * of 777000ms in 0.1.496 above was that floor too.
 */
public fun resumeFrom(
    localMs: Long?,
    remoteMs: Long?,
    durationMs: Long?,
    remoteAlreadyUsedMs: Long? = null,
): ResumeChoice {
    if (remoteMs == null) return ResumeChoice(localMs, Because.ONLY_LOCAL)
    // Before the floor was known it won, and was written into this device's store to the millisecond.
    val ownMs = localMs.takeUnless { it == remoteAlreadyUsedMs && it != null && isYouTubesFloor(it, durationMs) }
    return resumeFromOwn(ownMs, remoteMs, durationMs, remoteAlreadyUsedMs)
}

private fun resumeFromOwn(localMs: Long?, remoteMs: Long, durationMs: Long?, remoteAlreadyUsedMs: Long?): ResumeChoice {
    if (isYouTubesFloor(remoteMs, durationMs)) return ResumeChoice(localMs, Because.REMOTE_ONLY_SAYS_STARTED)
    // Already acted on once, and unmoved since: whatever happened here happened later. Ahead of
    // the local-is-null case deliberately — a device with NO position for an item it has already
    // resumed once has had that position taken away (marked unplayed, or played to the end), and
    // an echo of an old decision must not put it back.
    if (remoteMs == remoteAlreadyUsedMs) return ResumeChoice(localMs, Because.REMOTE_IS_OLD_NEWS)
    if (localMs == null) return ResumeChoice(remoteMs, Because.ONLY_REMOTE)
    val granularity = ((durationMs ?: 0L) / PERCENT).coerceAtLeast(MIN_GAP_MS)
    return if (remoteMs - localMs > granularity) {
        ResumeChoice(remoteMs, Because.REMOTE_IS_AHEAD)
    } else {
        // Includes remote BEHIND local, which is the common case on the device doing the watching:
        // our own pings are what put the number there, rounded down to a percent on the way.
        ResumeChoice(localMs, Because.LOCAL_IS_AS_GOOD)
    }
}

private fun isYouTubesFloor(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs > 0 && positionMs * PERCENT <= durationMs * YOUTUBE_FLOOR_PERCENT

/** The chosen position and why — the reason is logged, so a report can explain a surprising resume. */
public data class ResumeChoice(val positionMs: Long?, val because: Because)

/** Why a resume position was chosen, in the words a diagnostics report should use. */
public enum class Because {
    /** YouTube had nothing for it — a podcast, or a video the account has never seen. */
    ONLY_LOCAL,

    /** This device had nothing: watched somewhere else, and this is the whole point of the feature. */
    ONLY_REMOTE,

    /** Watched further elsewhere since this device last saw it. */
    REMOTE_IS_AHEAD,

    /** Local is level or further on, and it is exact where the remote is a rounded percent. */
    LOCAL_IS_AS_GOOD,

    /** The same remote number this device already acted on, so it says nothing about what came after. */
    REMOTE_IS_OLD_NEWS,

    /** YouTube's 10% floor: the account has started it, and that is all the number says. */
    REMOTE_ONLY_SAYS_STARTED,
}

/** YouTube reports whole percents, so one percent of the duration is the finest it can mean. */
private const val PERCENT = 100

/** The lowest `percentDurationWatched` YouTube reports, however little has been watched. */
private const val YOUTUBE_FLOOR_PERCENT = 10

/**
 * Below this, a percentage-derived difference says nothing. A short video's one percent is a couple
 * of seconds, which is well inside the noise of when a ping happened to fire.
 */
private const val MIN_GAP_MS = 60_000L
