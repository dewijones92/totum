package com.dewijones92.totum.playback

internal object SpuriousEnd {

    const val WINDOW_MS: Long = 3_000L
    const val AT_START_MS: Long = 1_000L

    fun reason(durationMs: Long?, positionMs: Long, sinceStartMs: Long?): String? {
        if (durationMs != null) return null
        if (positionMs > AT_START_MS) return null
        if (sinceStartMs == null || sinceStartMs > WINDOW_MS) return null
        return "its length is unknown, it is at ${positionMs}ms and it was started ${sinceStartMs}ms ago"
    }
}
