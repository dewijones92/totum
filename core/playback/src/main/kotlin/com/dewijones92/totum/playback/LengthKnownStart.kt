package com.dewijones92.totum.playback

internal class LengthKnownStart {
    private var asked: String? = null

    fun forget() {
        asked = null
    }

    fun shouldAsk(itemId: String, durationMs: Long?, positionMs: Long): Boolean {
        if (itemId == asked || durationMs == null || durationMs <= 0) return false
        asked = itemId
        return positionMs < UNMOVED_MS
    }
}

private const val UNMOVED_MS = 15_000L
