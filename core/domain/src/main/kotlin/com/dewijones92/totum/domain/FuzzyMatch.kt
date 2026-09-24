package com.dewijones92.totum.domain

import java.text.Normalizer

public object FuzzyMatch {

    public fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NOT_ALPHANUMERIC, " ")
            .trim()
            .replace(SPACES, " ")

    public fun matches(query: String, fields: List<String?>): Boolean {
        val tokens = normalise(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystacks = fields.filterNotNull().map(::normalise).filter { it.isNotEmpty() }
        val words = haystacks.flatMap { it.split(' ') }
        val compact = haystacks.map { it.replace(" ", "") }
        return tokens.all { token ->
            if (token.length < MIN_INFIX) {
                words.any { it.startsWith(token) }
            } else {
                (haystacks + compact).any { token in it } || words.any { word -> wordMatches(token, word) }
            }
        }
    }

    private fun wordMatches(token: String, word: String): Boolean =
        isAbbreviation(token, word) || withinTypos(token, word)

    private fun isAbbreviation(token: String, word: String): Boolean {
        if (token.length < MIN_ABBREVIATION || token.length >= word.length || token[0] != word[0]) return false
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
        val prefix = word.take(token.length + allowed)
        return minOf(distance(token, word), distance(token, prefix.take(token.length))) <= allowed
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var beforePrevious = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && isTransposition(a, b, i, j)) {
                    current[j] = minOf(current[j], beforePrevious[j - 2] + 1)
                }
            }
            beforePrevious = previous
            previous = current
        }
        return previous[b.length]
    }

    private fun isTransposition(a: String, b: String, i: Int, j: Int): Boolean =
        a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
    private const val MIN_ABBREVIATION = 3
    private const val MIN_INFIX = 3
    private const val MIN_TYPO_TOKEN = 4
    private const val LONG_TOKEN = 8
}

public fun <T> List<T>.fuzzyFiltered(query: String, fields: (T) -> List<String?>): List<T> =
    if (query.isBlank()) this else filter { FuzzyMatch.matches(query, fields(it)) }

public val MediaItem.searchableText: List<String?> get() = listOf(title, author, publisher)

public val MediaSource.searchableText: List<String?>
    get() = listOf(title, (this as? MediaSource.PodcastFeed)?.publisher)
