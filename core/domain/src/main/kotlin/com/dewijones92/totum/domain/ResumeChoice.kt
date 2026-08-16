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
 */
public fun resumeFrom(localMs: Long?, remoteMs: Long?, durationMs: Long?): ResumeChoice {
    if (remoteMs == null) return ResumeChoice(localMs, Because.ONLY_LOCAL)
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
}

/** YouTube reports whole percents, so one percent of the duration is the finest it can mean. */
private const val PERCENT = 100

/**
 * Below this, a percentage-derived difference says nothing. A short video's one percent is a couple
 * of seconds, which is well inside the noise of when a ping happened to fire.
 */
private const val MIN_GAP_MS = 60_000L
