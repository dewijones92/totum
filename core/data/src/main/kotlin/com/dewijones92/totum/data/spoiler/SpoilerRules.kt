package com.dewijones92.totum.data.spoiler

import kotlinx.serialization.Serializable

@Serializable
public data class SpoilerQuery(val id: String, val title: String, val channel: String = "")

@Serializable
public data class SpoilerRequest(val items: List<SpoilerQuery>)

@Serializable
public data class SpoilerVerdict(val id: String, val spoils: Boolean, val safeTitle: String = "")

@Serializable
public data class SpoilerAnswer(val verdicts: List<SpoilerVerdict>)

public data class SafeTitle(
    val title: String,
    val spoils: Boolean,
    val why: String,
    val overridesModel: Boolean = false,
)

public object SpoilerRules {
    public const val NEUTRAL: String = "🙈 Title hidden"

    private val resultVerbs = listOf(
        "beats?", "beaten", "defeat(?:s|ed)?", "wins?", "won", "winner", "champions?", "crowned",
        "triumph(?:s|ed)?", "victor(?:y|ious)", "lifts?", "stuns?", "stunned", "upsets?", "shocks?", "shocked",
        "retires?", "retired", "retirement", "walkover", "withdraws?", "withdrew", "eliminat(?:es|ed)",
        "knocked out", "knocks out", "advances?", "advanced", "survives?", "survived", "edges?", "edged",
        "outlasts?", "outlasted", "clinch(?:es|ed)?", "dominates?", "dominated", "thrash(?:es|ed)?",
        "crush(?:es|ed)?", "demolish(?:es|ed)?", "retain(?:s|ed|ing)", "through to",
        "saves? (?:\\w+ )?(?:match|championship|set|break) points?",
        "into the (?:final|semi-?finals?|quarter-?finals?)", "comeback",
    )

    private val hypeWords = listOf(
        "thriller", "epic", "marathon", "classic", "blockbuster", "iconic", "unforgettable", "insane", "crazy",
        "chaotic", "dramatic", "brutal", "battle", "dominant",
    )

    private val resultWords = Regex(
        "\\b(?:" + (resultVerbs + hypeWords).joinToString("|") + ")\\b|" +
            "\\b(?:in )?(?:straight|two|three|four|five)[- ]sets?\\b|" +
            "(?-i:\\bd\\.(?=\\s))|\\bdef\\b|" +
            "(?<![\\d:/.-])[0-7]\\s?[-–]\\s?[0-7](?:\\s?\\(\\d{1,2}\\))?(?![\\d:/.-])",
        RegexOption.IGNORE_CASE,
    )

    public fun reveals(text: String): Boolean = resultWords.containsMatchIn(text)

    public fun matchesIn(text: String): List<String> = resultWords.findAll(text).map { it.value }.toList()

    public fun decide(original: String, spoils: Boolean?, proposed: String?): SafeTitle {
        val rule = reveals(original)
        val clean = proposed?.trim().orEmpty().takeIf { it.isNotEmpty() }
        return when {
            spoils == false && !rule -> SafeTitle(original, false, "kept: no result in it")
            clean == null -> SafeTitle(NEUTRAL, true, "no rewrite offered (rule hit: $rule, model said $spoils)", true)
            reveals(clean) -> SafeTitle(NEUTRAL, true, "rewrite still names a result: ${matchesIn(clean)}", true)
            !sharesAWord(original, clean) -> SafeTitle(NEUTRAL, true, "rewrite shares no word with the title", true)
            clean.length > original.length + EXTRA -> SafeTitle(NEUTRAL, true, "rewrite is longer than the title", true)
            else -> SafeTitle(clean, true, "rewritten (rule hit: $rule, model said $spoils)", spoils == false)
        }
    }

    private fun sharesAWord(a: String, b: String): Boolean {
        val words = { s: String -> s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet() }
        return words(a).intersect(words(b)).isNotEmpty()
    }

    private const val EXTRA = 12
}
