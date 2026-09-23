package com.dewijones92.totum

/**
 * How an intent carrying a share reached the activity. Only [FRESH] is someone sharing now; the
 * others are Android handing back an intent that has already been seen.
 *
 * Report 0.1.346 fired one link five times over five hours, and report 0.1.514 put a Gill Gross
 * video over the news 48ms after a cold start. The fix in between marked the intent as handled, but
 * that mark lands only on this process's copy: the task keeps its own, so after the process is
 * killed the unmarked original comes back. Android says so itself, which is what this reads.
 */
internal enum class ShareArrival(val why: String) {
    FRESH("shared just now"),
    REOPENED_FROM_RECENTS("reopened from Recents, which replays the share the task was started with"),
    RESTORED("rebuilt from saved state, which replays the intent the activity last held"),
}

internal fun shareArrival(launchedFromHistory: Boolean, restored: Boolean): ShareArrival = when {
    launchedFromHistory -> ShareArrival.REOPENED_FROM_RECENTS
    restored -> ShareArrival.RESTORED
    else -> ShareArrival.FRESH
}
