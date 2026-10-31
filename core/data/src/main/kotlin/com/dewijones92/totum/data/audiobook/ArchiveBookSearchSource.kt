package com.dewijones92.totum.data.audiobook

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

public class ArchiveBookSearchSource(private val fetcher: HttpTextFetcher) : SearchSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome {
        if (after != null) return SearchOutcome.Success(Page.empty())
        val words = ArchiveBooks.wordsOf(query.value)
        if (words.isEmpty()) {
            Diag.log("audiobook", "\"${query.value}\" has no words to look for; not asking the Internet Archive")
            return SearchOutcome.Success(Page.last(emptyList()))
        }
        val body = when (val fetched = fetcher.fetch(ArchiveBooks.searchUrl(words, limit))) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure -> {
                Diag.warn("audiobook", "Internet Archive search for \"${query.value}\" failed: ${fetched.detail}")
                return SearchOutcome.Failure(fetched.detail)
            }
        }
        val docs = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["response"] as JsonObject)["docs"] as JsonArray
        }.getOrElse { return SearchOutcome.Failure("The Internet Archive answered something unreadable") }
        val books = docs.mapNotNull { (it as? JsonObject)?.toBook() }.distinctBy { it.identifier }
        Diag.log(
            "audiobook",
            "Internet Archive \"${query.value}\" -> ${books.size} book(s), " +
                "${books.count { it.librivox }} from LibriVox, of ${docs.size} row(s)",
        )
        return SearchOutcome.Success(Page.last(books))
    }

    private fun JsonObject.toBook(): SearchHit.Book? {
        val identifier = archiveText("identifier") ?: return null
        val title = archiveText("title") ?: return null
        val collections = archiveTexts("collection")
        val librivox = LIBRIVOX in collections
        val plays = (this["downloads"] as? JsonPrimitive)?.longOrNull
        val subtitle = listOfNotNull(
            archiveTexts("creator").joinToString(", ").ifBlank { null },
            if (librivox) "LibriVox" else "Internet Archive",
            plays?.let { "%,d plays".format(it) },
        ).joinToString(" · ")
        return SearchHit.Book(
            title = title,
            subtitle = subtitle,
            artworkUrl = ArchiveBooks.artworkUrl(identifier),
            feedUrl = ArchiveBooks.feedUrl(identifier),
            identifier = identifier,
            librivox = librivox,
        )
    }

    private companion object {
        const val LIBRIVOX = "librivoxaudio"
    }
}
