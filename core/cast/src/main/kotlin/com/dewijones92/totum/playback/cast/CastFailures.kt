package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap

public class NoReadableTracks(public val label: String, public val summary: String) :
    IllegalStateException("no readable tracks in $label ($summary)")

public class CastFailures(
    private val tvs: CastTvPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val threshold: Int = THRESHOLD,
    private val windowMs: Long = WINDOW_MS,
) {
    private val seen = ConcurrentHashMap<String, MutableList<Long>>()
    private val told = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    public fun failed(mediaId: String, what: String, cause: Throwable): Boolean {
        val at = now()
        val recent = seen.getOrPut(mediaId) { mutableListOf() }
        recent.removeAll { at - it > windowMs }
        recent += at
        val reason = reasonOf(cause)
        Diag.warn(
            "cast",
            "$mediaId: $what failed (${recent.size} of $threshold before the user is told): $reason",
            cause,
        )
        if (recent.size < threshold || !told.add(mediaId)) return false
        Diag.warn("cast", "$mediaId: ${recent.size} failures in ${windowMs / MS_PER_S}s; telling the user: $reason")
        tvs.failed(mediaId, reason)
        return true
    }

    @Synchronized
    public fun worked(mediaId: String) {
        seen.remove(mediaId)
        if (told.remove(mediaId)) {
            Diag.log("cast", "$mediaId: it works now; the failure notice is cleared")
            tvs.failed(mediaId, null)
        }
    }

    @Synchronized
    public fun forget(mediaId: String) {
        seen.remove(mediaId)
        told.remove(mediaId)
    }

    @Synchronized
    public fun clear() {
        seen.clear()
        told.clear()
    }

    public companion object {
        public const val THRESHOLD: Int = 3
        public const val WINDOW_MS: Long = 60_000L
        private const val MS_PER_S = 1_000L
        public const val TRACK_LIST_AT_THE_END: String =
            "this file's track list is at its end, so the phone can't read it; the Pi will repair it when it is filed"

        public fun reasonOf(cause: Throwable): String = when {
            generateSequence(cause) { it.cause }.any { it is NoReadableTracks } -> TRACK_LIST_AT_THE_END
            else -> cause.message?.takeIf { it.isNotBlank() } ?: cause::class.java.simpleName
        }
    }
}
