package com.dewijones92.totum.playback.cast

public fun soundOnlyOnTheTvByName(name: String): String? = WORDS.firstNotNullOfOrNull { (word, pattern) ->
    word.takeIf { pattern.containsMatchIn(name) }
}

private val WORDS = listOf(
    "XviD" to Regex("""(?i)(?<![a-z0-9])xvid(?![a-z])"""),
    "DivX" to Regex("""(?i)(?<![a-z0-9])divx"""),
    "AV1" to Regex("""(?i)(?<![a-z0-9])av1(?![0-9a-z])"""),
    "AVI" to Regex("""(?i)(\.|(?<![a-z0-9]))avi(?![a-z0-9])"""),
)
