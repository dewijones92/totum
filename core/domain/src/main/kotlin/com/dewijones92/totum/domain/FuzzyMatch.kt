package com.dewijones92.totum.domain

import java.text.Normalizer
import kotlin.math.abs

public class FuzzyText internal constructor(internal val words: List<String>, internal val phrases: List<String>) {
    internal val joinedStarts: List<Pair<String, IntArray>> = phrases.map { phrase ->
        val joined = StringBuilder()
        val starts = mutableListOf<Int>()
        phrase.split(' ').forEach { part ->
            part.forEachIndexed { i, c ->
                if (i == 0 || c.isDigit() != part[i - 1].isDigit()) starts += joined.length
                joined.append(c)
            }
        }
        joined.toString() to starts.toIntArray()
    }
}

public class FuzzyQuery internal constructor(internal val tokens: List<String>) {
    public val hasTerms: Boolean get() = tokens.isNotEmpty()
}

public object FuzzyMatch {

    public fun normalise(text: String): String =
        Normalizer.normalize(
            Normalizer.normalize(text.replace("ß", "ss"), Normalizer.Form.NFD).replace(COMBINING_MARKS, ""),
            Normalizer.Form.NFC,
        )
            .lowercase()
            .replace(NOT_ALPHANUMERIC, " ")
            .trim()
            .replace(SPACES, " ")

    public fun text(fields: List<String?>): FuzzyText {
        val phrases = fields.filterNotNull().map(::normalise).filter { it.isNotEmpty() }
        return FuzzyText(phrases.flatMap { it.split(' ') }, phrases)
    }

    public fun query(query: String): FuzzyQuery = FuzzyQuery(normalise(query).split(' ').filter { it.isNotEmpty() })

    public fun hasTerms(query: String): Boolean = query(query).hasTerms

    public fun matches(query: String, fields: List<String?>): Boolean = matches(query(query), text(fields))

    public fun matches(query: FuzzyQuery, text: FuzzyText): Boolean = query.tokens.all { token -> found(token, text) }

    private fun found(token: String, text: FuzzyText): Boolean = when {
        token.any(Char::isDigit) -> text.startsAWord(token)
        token.isUnspaced() -> text.phrases.any { token in it }
        token.length < MIN_INFIX -> text.words.any { it.startsWith(token) }
        else -> text.phrases.any { token in it } ||
            text.startsAWord(token) ||
            text.words.any { word -> WordSimilarity.close(token, word) }
    }

    private fun FuzzyText.startsAWord(token: String): Boolean =
        joinedStarts.any { (joined, starts) -> starts.any { joined.startsWith(token, it) } }

    private fun String.isUnspaced(): Boolean = any { Character.UnicodeScript.of(it.code) in UNSPACED_SCRIPTS }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
    )
    private const val MIN_INFIX = 3
}

private object WordSimilarity {

    fun close(token: String, word: String): Boolean =
        !word.any(Char::isDigit) && (isAbbreviation(token, word) || withinTypos(token, word))

    private fun isAbbreviation(token: String, word: String): Boolean {
        if (token.length < MIN_ABBREVIATION || token.length >= word.length || token[0] != word[0]) return false
        if (token.any { it in VOWELS }) return false
        var at = 0
        for (c in word) if (at < token.length && c == token[at]) at++
        return at == token.length
    }

    private fun withinTypos(token: String, word: String): Boolean {
        val allowed = when {
            token.length >= LONG_TOKEN -> 2
            token.length >= MIN_TYPO_TOKEN -> 1
            else -> return false
        }
        if (token[0] != word[0] || word.length < token.length - allowed) return false
        val shortest = maxOf(1, token.length - allowed)
        val longest = minOf(word.length, token.length + allowed)
        val lengths = (shortest..longest).filter { token.length >= MIN_PREFIX_TYPO || it != token.length }
        return (abs(word.length - token.length) <= allowed && distance(token, word) <= allowed) ||
            lengths.any { length -> distance(token, word.substring(0, length)) <= allowed }
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var beforePrevious = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && isTransposition(a, b, i, j)) {
                    current[j] = minOf(current[j], beforePrevious[j - 2] + 1)
                }
            }
            val spare = beforePrevious
            beforePrevious = previous
            previous = current
            current = spare
        }
        return previous[b.length]
    }

    private fun isTransposition(a: String, b: String, i: Int, j: Int): Boolean =
        a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]

    private const val MIN_ABBREVIATION = 4
    private const val VOWELS = "aeiouy"
    private const val MIN_TYPO_TOKEN = 5
    private const val MIN_PREFIX_TYPO = 6
    private const val LONG_TOKEN = 8
}

public fun <T> List<T>.fuzzyFiltered(query: String, fields: (T) -> List<String?>): List<T> {
    val parsed = FuzzyMatch.query(query)
    return if (!parsed.hasTerms) this else filter { FuzzyMatch.matches(parsed, FuzzyMatch.text(fields(it))) }
}

public val MediaItem.searchableText: List<String?> get() = listOf(title, author, publisher)

public val MediaSource.searchableText: List<String?>
    get() = listOf(title, (this as? MediaSource.PodcastFeed)?.publisher)
