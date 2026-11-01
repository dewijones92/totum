package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

public class AskCopies(private val sources: (AskKind) -> List<SearchSource>, private val perSuggestion: Int = VETTED) {

    public suspend fun find(suggestion: Suggestion): List<SearchHit> = coroutineScope {
        val query = suggestion.searchWords.ifBlank { suggestion.label }
        val found = if (suggestion.kind == AskKind.RADIO) {
            query.split(';').map(String::trim).filter(String::isNotEmpty)
                .map { seed -> async { search(suggestion.kind, seed, 1).firstOrNull() } }
                .awaitAll().filterNotNull()
        } else {
            search(suggestion.kind, query, perSuggestion).take(perSuggestion)
        }
        Diag.log(
            "ask",
            "\"$query\" (${suggestion.kind}) -> ${found.size} copies: ${found.joinToString("; ") { it.title }}"
        )
        found
    }

    private suspend fun search(kind: AskKind, query: String, limit: Int): List<SearchHit> = coroutineScope {
        sources(kind).map { source ->
            async {
                when (val outcome = source.search(SearchQuery(query), limit, null)) {
                    is SearchOutcome.Success -> outcome.page.items
                    is SearchOutcome.Failure -> {
                        Diag.warn("ask", "search \"$query\" for $kind failed: ${outcome.detail}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
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
    val items: List<PlayableItem> = emptyList(),
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
