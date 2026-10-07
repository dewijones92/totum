package com.dewijones92.totum.playback

internal object YoungStreamUrl {
    const val LEASE_MS = 21_600_000L
    const val VALID_AFTER_MS = 4_800L
    const val YOUNG_FOR_MS = 15_000L
    private const val MIN_WAIT_MS = 250L
    private const val SECOND_WAIT_MS = 750L
    private const val LATER_WAIT_MS = 1_500L
    private val EXPIRE = Regex("""[?&]expire=(\d+)""")
    private const val MILLIS_PER_SECOND = 1_000L

    fun issuedAtMs(url: String): Long? =
        EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { it * MILLIS_PER_SECOND - LEASE_MS }

    fun ageMs(url: String, nowMs: Long): Long? = issuedAtMs(url)?.let { nowMs - it }

    val window: YoungUrlWindow = YoungUrlWindow()

    fun retryDelayMs(url: String, nowMs: Long, errorCount: Int, window: YoungUrlWindow = this.window): Long? {
        val issued = issuedAtMs(url) ?: return null
        if (nowMs - issued !in 0..YOUNG_FOR_MS) return null
        val untilValid = issued + window.validAfterMs(nowMs) - nowMs
        return when {
            errorCount <= 1 -> untilValid.coerceAtLeast(MIN_WAIT_MS)
            errorCount == 2 -> untilValid.coerceAtLeast(SECOND_WAIT_MS)
            else -> untilValid.coerceAtLeast(LATER_WAIT_MS)
        }
    }
}
