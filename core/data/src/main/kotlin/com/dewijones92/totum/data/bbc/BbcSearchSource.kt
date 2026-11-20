package com.dewijones92.totum.data.bbc

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.SearchSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

public class BbcSearchSource(private val fetcher: HttpTextFetcher) : SearchSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome {
        if (after != null) return SearchOutcome.Success(Page.empty())
        val body = when (val fetched = fetcher.fetch(BbcProgrammes.searchUrl(query.value, limit))) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure -> {
                Diag.warn("bbc", "BBC iPlayer search for \"${query.value}\" failed: ${fetched.detail}")
                return SearchOutcome.Failure(fetched.detail)
            }
        }
        val results = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["new_search"] as JsonObject)["results"] as JsonArray
        }.getOrElse {
            Diag.warn("bbc", "BBC iPlayer search for \"${query.value}\" answered something unreadable: ${it.message}")
            return SearchOutcome.Failure("BBC iPlayer answered something unreadable")
        }
        val programmes = results.mapNotNull { (it as? JsonObject)?.toProgramme() }.distinctBy { it.feedUrl }
        Diag.log(
            "bbc",
            "BBC iPlayer \"${query.value}\" -> ${programmes.size} programme(s) of ${results.size} result(s); " +
                "episodes and clips left out: ${results.size - programmes.size}",
        )
        return SearchOutcome.Success(Page.last(programmes))
    }

    private fun JsonObject.toProgramme(): SearchHit.Programme? {
        if (text("type") != "programme") return null
        val pid = text("id") ?: return null
        val title = text("title") ?: return null
        val episodes = (this["count"] as? JsonPrimitive)?.longOrNull
        val channel = ((this["master_brand"] as? JsonObject)?.get("titles") as? JsonObject)?.text("small")
        val category = (this["labels"] as? JsonObject)?.text("category")
        return SearchHit.Programme(
            title = title,
            subtitle = listOfNotNull(
                channel,
                episodes?.let { if (it == 1L) "1 episode" else "$it episodes" },
                category,
            ).joinToString(" · ").ifBlank { null },
            artworkUrl = BbcProgrammes.imageUrl((this["images"] as? JsonObject)?.text("standard")),
            feedUrl = BbcProgrammes.feedUrl(pid),
            pid = pid,
        )
    }
}
