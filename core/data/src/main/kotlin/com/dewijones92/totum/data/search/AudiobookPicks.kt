package com.dewijones92.totum.data.search

public object AudiobookPicks {

    private val WORD = Regex("""[\p{L}\p{N}]+""")

    public fun merged(query: String, books: List<SearchHit.Book>, torrents: List<SearchHit.Torrent>): List<SearchHit> {
        val wanted = words(query)
        fun match(title: String): Double =
            if (wanted.isEmpty()) {
                0.0
            } else {
                words(
                    title
                ).let { have -> wanted.count { it in have }.toDouble() / wanted.size }
            }
        val freeByMatch = books.groupBy { match(it.title) }
        val torrentsByMatch = torrents.sortedByDescending { it.seeders }.groupBy { match(it.title) }
        return (freeByMatch.keys + torrentsByMatch.keys).distinct().sortedDescending().flatMap { score ->
            takingTurns(freeByMatch[score].orEmpty(), torrentsByMatch[score].orEmpty())
        }
    }

    private fun takingTurns(free: List<SearchHit>, torrents: List<SearchHit>): List<SearchHit> =
        (0 until maxOf(free.size, torrents.size)).flatMap { i ->
            listOfNotNull(
                free.getOrNull(i),
                torrents.getOrNull(i)
            )
        }

    private fun words(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value }.toSet()
}
