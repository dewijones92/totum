package com.dewijones92.totum.playback

internal class SilenceCutStats(
    private val everyReleases: Int = LOG_EVERY_RELEASES,
    private val everyMs: Long = LOG_EVERY_MS,
) {
    private var heardInAll = 0L
    private var jumps = 0
    private var cuts = 0
    private var cutTotalUs = 0L
    private var longestCutUs = 0L
    private var jumpedUs = 0L
    private var longestJumpUs = 0L
    private var leftOwing = 0
    private var lastLineAtMs = 0L
    private var droppedAtLastLine = 0L

    @Suppress("LongParameterList")
    fun released(
        cutUs: Long,
        jumpUs: Long,
        owedUs: Long,
        video: Boolean,
        droppedFrames: Long,
        nowMs: Long,
    ): String? {
        heardInAll++
        jumps++
        if (cutUs > 0) {
            cuts++
            cutTotalUs += cutUs
            longestCutUs = maxOf(longestCutUs, cutUs)
        }
        jumpedUs += jumpUs
        longestJumpUs = maxOf(longestJumpUs, jumpUs)
        if (owedUs > 0) leftOwing++
        val first = heardInAll == 1L
        if (!first && jumps < everyReleases && nowMs - lastLineAtMs < everyMs) return null
        val line = "heard cuts (${if (video) "video playing" else "no video"}): $cuts cut(s) of " +
            "${ms(cutTotalUs)}ms in all, longest ${ms(longestCutUs)}ms, moved the clock in $jumps jump(s) of " +
            "${ms(jumpedUs)}ms in all, longest ${ms(longestJumpUs)}ms, $leftOwing of them with more still to move " +
            "(the video cap); video frames dropped since the last line ${droppedFrames - droppedAtLastLine}; " +
            "$heardInAll jump(s) since the stream began"
        jumps = 0
        cuts = 0
        cutTotalUs = 0L
        longestCutUs = 0L
        jumpedUs = 0L
        longestJumpUs = 0L
        leftOwing = 0
        lastLineAtMs = nowMs
        droppedAtLastLine = droppedFrames
        return line
    }

    private fun ms(us: Long) = us / MICROS_PER_MS

    private companion object {
        const val LOG_EVERY_RELEASES = 50
        const val LOG_EVERY_MS = 30_000L
        const val MICROS_PER_MS = 1_000L
    }
}
