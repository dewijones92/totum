package com.dewijones92.totum.playback

internal class HeardClock {

    private val checkpoints = ArrayDeque<Checkpoint>()
    private var baselineUs: Long? = null
    private var heldBackUs = 0L
    private var inputEnded = false

    var releasedUs: Long = 0L
        private set

    var heardUs: Long = 0L
        private set

    val sinceStartUs: Long get() = heardUs - (baselineUs ?: heardUs)

    val waitingOnFlushes: Int get() = checkpoints.size

    val started: Boolean get() = baselineUs != null

    fun seeked() {
        checkpoints.clear()
        restart()
    }

    fun processorsFlushed(previousSkippedBy: (Long) -> Long) {
        checkpoints.addLast(Checkpoint(previousBaselineUs = baselineUs, previousSkippedBy = previousSkippedBy))
        restart()
    }

    fun streamStarts(presentationTimeUs: Long) {
        if (baselineUs != null) return
        baselineUs = presentationTimeUs
        checkpoints.filter { it.baselineUs == null }.forEach { it.baselineUs = presentationTimeUs }
    }

    fun inputEnded() {
        inputEnded = true
    }

    fun position(countedUs: Long, allSkippedUs: Long, skippedHeardBy: (Long) -> Long): Long {
        releasedUs = 0L
        heardUs = countedUs - allSkippedUs
        while (checkpoints.firstOrNull()?.baselineUs?.let { heardUs >= it } == true) checkpoints.removeFirst()
        checkpoints.firstOrNull()?.let { checkpoint ->
            val position = checkpoint.positionBefore(heardUs)
            releasedUs = checkpoint.newlyCarriedUs
            return position
        }
        val baseline = baselineUs ?: return countedUs
        val skippedHeardUs = skippedHeardBy(heardUs - baseline + if (inputEnded) END_SLACK_US else 0L)
        val holdingUs = allSkippedUs - skippedHeardUs
        if (holdingUs < heldBackUs) releasedUs = heldBackUs - holdingUs
        heldBackUs = holdingUs
        return heardUs + skippedHeardUs
    }

    private fun restart() {
        baselineUs = null
        heldBackUs = 0L
        inputEnded = false
    }

    private class Checkpoint(val previousBaselineUs: Long?, val previousSkippedBy: (Long) -> Long) {
        var baselineUs: Long? = null
        private var carriedUs = 0L
        var newlyCarriedUs = 0L
            private set

        fun positionBefore(heardUs: Long): Long {
            val carried = previousBaselineUs?.let { previousSkippedBy(heardUs - it) } ?: 0L
            newlyCarriedUs = (carried - carriedUs).coerceAtLeast(0L)
            carriedUs = maxOf(carriedUs, carried)
            val position = heardUs + carried
            return baselineUs?.let { minOf(position, it) } ?: position
        }
    }

    private companion object {
        const val END_SLACK_US = 100_000L
    }
}
