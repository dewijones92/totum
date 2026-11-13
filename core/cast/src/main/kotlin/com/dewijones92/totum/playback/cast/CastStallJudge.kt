package com.dewijones92.totum.playback.cast

public class CastStallJudge(
    private val stalls: StallsBeforeStepDown = StallsBeforeStepDown(),
    private val now: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = GRACE_MS,
    private val neverEndsMs: Long = NEVER_ENDS_MS,
) {
    public enum class Kind { NOTHING, NOT_A_STALL, SAME_STALL, FIRST_STALL, STEP_DOWN }

    public class Verdict(public val kind: Kind, public val why: String)

    private var readyAtMs = NEVER
    private var movedAtMs = NEVER
    private var bufferingSinceMs = NEVER
    private var episodeJudged = false

    @Synchronized
    public fun ready() {
        readyAtMs = now()
        bufferingSinceMs = NEVER
        episodeJudged = false
    }

    @Synchronized
    public fun moved() {
        movedAtMs = now()
    }

    @Synchronized
    public fun itemChanged(mediaId: String?) {
        mediaId?.let(stalls::forget)
        readyAtMs = NEVER
        bufferingSinceMs = NEVER
        episodeJudged = false
        movedAtMs = now()
    }

    @Synchronized
    public fun afterStepDown() {
        readyAtMs = NEVER
        bufferingSinceMs = NEVER
        episodeJudged = false
        movedAtMs = now()
    }

    @Synchronized
    public fun buffering(mediaId: String, positionMs: Long, playWhenReady: Boolean): Verdict {
        val at = now()
        if (bufferingSinceMs == NEVER) {
            bufferingSinceMs = at
            episodeJudged = false
        }
        val reason = when {
            readyAtMs == NEVER -> "it has not played yet on the TV (still starting)"
            !playWhenReady -> "it is paused"
            at - movedAtMs < graceMs -> "someone just moved it (seek, pause or item change)"
            else -> null
        }
        if (reason != null) {
            return Verdict(
                Kind.NOT_A_STALL,
                "$mediaId: the TV is buffering at ${positionMs}ms; not counted as a stall yet - $reason " +
                    "(still buffering ${neverEndsMs / MS_PER_S}s after that counts)",
            )
        }
        return when (stalls.count(mediaId, positionMs)) {
            StallsBeforeStepDown.Count.SAME_STALL -> Verdict(
                Kind.SAME_STALL,
                "$mediaId: the TV buffered again at ${positionMs}ms, too soon after the last stall to be a new " +
                    "one; counted once",
            )
            StallsBeforeStepDown.Count.COUNTED -> Verdict(
                Kind.FIRST_STALL,
                "$mediaId: the TV stalled at ${positionMs}ms after playing; first stall, counting",
            )
            StallsBeforeStepDown.Count.STEP_DOWN -> {
                episodeJudged = true
                Verdict(
                    Kind.STEP_DOWN,
                    "$mediaId: the TV stalled at ${positionMs}ms after playing; second stall within " +
                        "${StallsBeforeStepDown.WINDOW_MS / MS_PER_S}s, stepping down",
                )
            }
        }
    }

    @Synchronized
    public fun tick(mediaId: String, positionMs: Long, playWhenReady: Boolean): Verdict {
        val nothing = Verdict(Kind.NOTHING, "")
        val waiting = bufferingSinceMs != NEVER && !episodeJudged
        val playedBefore = readyAtMs != NEVER
        if (!waiting || !playedBefore || !playWhenReady) return nothing
        val waitedFrom = maxOf(bufferingSinceMs, movedAtMs)
        val waited = now() - waitedFrom
        if (waited < neverEndsMs) return nothing
        episodeJudged = true
        stalls.forget(mediaId)
        return Verdict(
            Kind.STEP_DOWN,
            "$mediaId: the TV has been buffering for ${waited / MS_PER_S}s at ${positionMs}ms without recovering " +
                "(it played before, it is not paused); counting that as a stall that steps down",
        )
    }

    public companion object {
        public const val GRACE_MS: Long = 4_000L
        public const val NEVER_ENDS_MS: Long = 12_000L
        private const val NEVER = Long.MIN_VALUE
        private const val MS_PER_S = 1_000L
    }
}
