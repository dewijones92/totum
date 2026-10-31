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
        val strict = when (val found = booksFor(ArchiveBooks.searchUrl(words, limit), query)) {
            is Found.Books -> found.books
            is Found.Failed -> return SearchOutcome.Failure(found.detail)
        }
        val loose = if (strict.size < LOOSE_BELOW) {
            (booksFor(ArchiveBooks.searchUrl(words, limit, loose = true), query) as? Found.Books)?.books.orEmpty()
        } else {
            emptyList()
        }
        val books = (strict + loose).distinctBy { it.identifier }
        Diag.log(
            "audiobook",
            "Internet Archive \"${query.value}\" -> ${books.size} book(s), " +
                "${books.count { it.librivox }} from LibriVox " +
                "(${strict.size} by title or author" +
                (if (strict.size < LOOSE_BELOW) ", ${books.size - strict.size} more from a looser search)" else ")"),
        )
        return SearchOutcome.Success(Page.last(books))
    }

    private sealed interface Found {
        data class Books(val books: List<SearchHit.Book>) : Found
        data class Failed(val detail: String) : Found
    }

    private suspend fun booksFor(url: com.dewijones92.totum.common.HttpUrl, query: SearchQuery): Found {
        val body = when (val fetched = fetcher.fetch(url)) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure -> {
                Diag.warn("audiobook", "Internet Archive search for \"${query.value}\" failed: ${fetched.detail}")
                return Found.Failed(fetched.detail)
            }
        }
        val docs = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["response"] as JsonObject)["docs"] as JsonArray
        }.getOrElse { return Found.Failed("The Internet Archive answered something unreadable") }
        return Found.Books(docs.mapNotNull { (it as? JsonObject)?.toBook() }.distinctBy { it.identifier })
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
        const val LOOSE_BELOW = 5
    }
}
