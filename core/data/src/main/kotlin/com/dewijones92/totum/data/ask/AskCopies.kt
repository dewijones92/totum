package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.SearchSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

public class AskCopies(private val sources: (AskKind) -> List<SearchSource>, private val perSuggestion: Int = VETTED) {

    public suspend fun find(suggestion: Suggestion): List<SearchHit> = coroutineScope {
        val query = suggestion.searchWords.ifBlank { suggestion.label }
        sources(suggestion.kind).map { source ->
            async {
                when (val outcome = source.search(SearchQuery(query), perSuggestion, null)) {
                    is SearchOutcome.Success -> outcome.page.items
                    is SearchOutcome.Failure -> {
                        Diag.warn("ask", "search \"$query\" for ${suggestion.kind} failed: ${outcome.detail}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten().take(perSuggestion)
            .also { found ->
                val titles = found.joinToString("; ") { it.title }
                Diag.log("ask", "\"$query\" (${suggestion.kind}) -> ${found.size} copies: $titles")
            }
    }

    public companion object {
        public const val VETTED: Int = 6
    }
}

public fun SearchHit.toCopy(): CopyToVet = when (this) {
    is SearchHit.Torrent -> CopyToVet(title, sizeBytes, "$seeders seeders" + (indexer?.let { " · $it" } ?: ""))
    else -> CopyToVet(title, detail = subtitle)
}

public data class AskCard(
    val suggestion: Suggestion,
    val copies: List<SearchHit> = emptyList(),
    val verdicts: Map<Int, Verdict> = emptyMap(),
    val searched: Boolean = false,
    val vetted: Boolean = false,
) {
    public val best: SearchHit? get() = copies.indices.firstOrNull { verdicts[it]?.ok != false }?.let(copies::get)
    public val rejected: List<Pair<SearchHit, String>>
        get() = copies.indices.mapNotNull { i -> verdicts[i]?.takeIf { !it.ok }?.let { copies[i] to it.reason } }

    public fun judged(by: VetAnswer, item: Int): AskCard =
        copy(
            verdicts = by.verdicts.filter { it.item == item && it.copy in copies.indices }.associateBy { it.copy },
            vetted = true,
        )
}

public fun List<AskCard>.vetRequest(): VetRequest =
    VetRequest(map { VetItem(it.suggestion, it.copies.map(SearchHit::toCopy)) })
