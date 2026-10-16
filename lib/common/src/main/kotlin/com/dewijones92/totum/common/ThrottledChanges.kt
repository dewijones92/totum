package com.dewijones92.totum.common

public class ThrottledChanges(private val minGapMs: Long) {
    private var lastKey: String? = null
    private var lastLoggedAt: Long? = null
    private var folded = 0

    public fun offer(key: String, nowMs: Long): Int? {
        if (key == lastKey) return null
        lastKey = key
        val since = lastLoggedAt?.let { nowMs - it }
        if (since != null && since < minGapMs) {
            folded++
            return null
        }
        lastLoggedAt = nowMs
        return folded.also { folded = 0 }
    }
}
