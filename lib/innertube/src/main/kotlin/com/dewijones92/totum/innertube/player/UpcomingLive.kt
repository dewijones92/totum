package com.dewijones92.totum.innertube.player

import java.time.Instant

public data class UpcomingLive(val startsAt: Instant?, val said: String) {
    public companion object {
        private val WORDS = Regex(
            """live event will begin|premieres? (in|on)|will premiere|waiting for .{1,80} to (start|begin)""",
            RegexOption.IGNORE_CASE,
        )

        public fun soundsLikeOne(text: String?): Boolean = text != null && WORDS.containsMatchIn(text)
    }
}

public sealed interface LiveCheck {
    public data class Live(val via: String) : LiveCheck

    public data class Ended(val via: String) : LiveCheck

    public data class StillUpcoming(val upcoming: UpcomingLive, val via: String) : LiveCheck

    public data class Unknown(val why: String) : LiveCheck
}
