package com.dewijones92.totum.playback

internal class HeardClock {

    private var baselineUs: Long? = null
    private var carriedUs = 0L
    private var heldBackUs = 0L
    private var inputEnded = false

    var releasedUs: Long = 0L
        private set

    var heardUs: Long = 0L
        private set

    val sinceStartUs: Long get() = heardUs - (baselineUs ?: heardUs)

    fun seeked() {
        processorsFlushed(carriedSkipUs = 0L)
    }

    fun processorsFlushed(carriedSkipUs: Long) {
        baselineUs = null
        carriedUs = carriedSkipUs
        heldBackUs = 0L
        inputEnded = false
    }

    fun streamStarts(presentationTimeUs: Long) {
        if (baselineUs == null) baselineUs = presentationTimeUs
    }

    fun inputEnded() {
        inputEnded = true
    }

    fun position(countedUs: Long, allSkippedUs: Long, skippedHeardBy: (Long) -> Long): Long {
        releasedUs = 0L
        val baseline = baselineUs ?: return countedUs + carriedUs
        heardUs = countedUs - allSkippedUs
        if (heardUs < baseline) return minOf(heardUs + carriedUs, baseline)
        carriedUs = 0L
        val skippedHeardUs = skippedHeardBy(heardUs - baseline + if (inputEnded) END_SLACK_US else 0L)
        val holdingUs = allSkippedUs - skippedHeardUs
        if (holdingUs < heldBackUs) releasedUs = heldBackUs - holdingUs
        heldBackUs = holdingUs
        return heardUs + skippedHeardUs
    }

    private companion object {
        const val END_SLACK_US = 100_000L
    }
}
