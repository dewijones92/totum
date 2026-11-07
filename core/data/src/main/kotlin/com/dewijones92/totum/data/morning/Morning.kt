package com.dewijones92.totum.data.morning

import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.squashed
import kotlinx.serialization.Serializable

@Serializable
public data class ForYouRequest(
    val taste: Taste,
    val have: List<String> = emptyList(),
    val earlier: List<String> = emptyList(),
    val videos: Int = VIDEOS,
    val podcasts: Int = PODCASTS,
) {
    public companion object {
        public const val VIDEOS: Int = 6
        public const val PODCASTS: Int = 4
    }
}

@Serializable
public data class ForYouAnswer(val picks: List<Suggestion>)

@Serializable
public data class GroupHeadlines(val group: String, val titles: List<String>)

@Serializable
public data class MorningDigestRequest(val groups: List<GroupHeadlines>)

@Serializable
public data class MorningDigestAnswer(val lines: List<String>)

public object MorningRules {
    public const val MAX_LINES: Int = 2
    private const val MAX_LINE = 150
    private const val MAX_WHY = 160
    private const val MIN_WORD = 4
    private const val MIN_SENTENCE = 30

    private val markdownLink = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val markup = Regex("""[*_`#>]+""")

    public fun tidyForYou(request: ForYouRequest, raw: ForYouAnswer): ForYouAnswer {
        val subscribed = (request.taste.channels + request.taste.shows).map { it.squashed() }.filter { it.isNotEmpty() }
        val owned = (request.have + request.earlier).map { it.squashed() }.toSet()
        val seen = mutableSetOf<String>()
        val kept = raw.picks.mapNotNull { pick ->
            val key = pick.title.squashed()
            val dropped = when {
                pick.kind != AskKind.YOUTUBE && pick.kind != AskKind.PODCAST -> "kind ${pick.kind} is not wanted"
                key.isEmpty() -> "no title"
                subscribed.any { it == key || (it.length > MIN_WORD && key.contains(it)) } -> "already subscribed to"
                key in owned -> "already have or picked lately"
                !seen.add(key) -> "named twice"
                else -> null
            }
            if (dropped != null) {
                null
            } else {
                pick.copy(year = null, alreadyHave = false, why = plain(pick.why).take(MAX_WHY))
            }
        }
        val videos = kept.filter { it.kind == AskKind.YOUTUBE }.take(request.videos)
        val podcasts = kept.filter { it.kind == AskKind.PODCAST }.take(request.podcasts)
        return ForYouAnswer((videos + podcasts))
    }

    public fun tidyDigest(request: MorningDigestRequest, raw: MorningDigestAnswer): MorningDigestAnswer {
        val known = (request.groups.map { it.group } + request.groups.flatMap { it.titles })
            .flatMap(::words).toSet()
        val lines = raw.lines.map(::plain).filter { it.isNotEmpty() }.map(::wholeSentence)
            .filter { line -> words(line).any { it in known } }
        return MorningDigestAnswer(lines.take(MAX_LINES))
    }

    public fun fallbackDigest(request: MorningDigestRequest): MorningDigestAnswer {
        val busy = request.groups.filter { it.titles.isNotEmpty() }.sortedByDescending { it.titles.size }
        val lines = busy.take(MAX_LINES).map { group ->
            val first = group.titles.first()
            val more = group.titles.size - 1
            "🆕 ${group.group}: $first" + if (more > 0) " and $more more" else ""
        }
        return MorningDigestAnswer(lines)
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_WORD }

    private fun wholeSentence(line: String): String {
        if (line.length <= MAX_LINE) return line
        val end = line.take(MAX_LINE).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end >= MIN_SENTENCE) line.take(end + 1) else line.take(MAX_LINE).substringBeforeLast(' ')
    }

    private fun plain(text: String): String = text
        .replace(markdownLink) { it.groupValues[1] }
        .replace(markup, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
