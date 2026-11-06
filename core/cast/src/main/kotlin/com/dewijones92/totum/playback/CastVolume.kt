package com.dewijones92.totum.playback

public data class CastVolume(val level: Int, val max: Int, val muted: Boolean) {
    init {
        require(max > 0) { "max must be positive" }
        require(level in 0..max) { "level $level must be within 0..$max" }
    }

    public val fraction: Float get() = level.toFloat() / max

    public val percent: Int get() = Math.round(fraction * PERCENT)

    public companion object {
        private const val PERCENT = 100

        public fun levelFor(fraction: Float, max: Int): Int = Math.round(fraction.coerceIn(0f, 1f) * max)

        public fun of(level: Int, max: Int, muted: Boolean): CastVolume? =
            max.takeIf { it > 0 }?.let { CastVolume(level.coerceIn(0, it), it, muted) }
    }
}
