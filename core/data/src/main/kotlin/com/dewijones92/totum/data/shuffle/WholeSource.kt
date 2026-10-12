package com.dewijones92.totum.data.shuffle

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

public enum class SourcePart { UPLOADS, SHORTS }

public fun interface SourcePages {
    public suspend fun page(source: MediaSource, part: SourcePart, after: PageToken?): Page<MediaItem>?
}

public data class Gathered(val items: List<MediaItem>, val pages: Int, val unfinished: List<String>)

public class WholeSource(
    private val pages: SourcePages,
    private val attemptsPerPage: Int = 3,
    private val runawayPageLimit: Int = 2_000,
) {
    public suspend fun everything(
        sources: List<MediaSource>,
        includeShorts: Boolean,
        progress: (Int) -> Unit = {},
    ): Gathered = coroutineScope {
        val parts = if (includeShorts) SourcePart.entries else listOf(SourcePart.UPLOADS)
        val loaded = AtomicInteger()
        val walks = sources.flatMap { source -> parts.map { part -> async { walk(source, part, loaded, progress) } } }
            .awaitAll()
        val items = walks.flatMap { it.items }.distinctBy { it.id }
        val gathered = Gathered(items, walks.sumOf { it.pages }, walks.mapNotNull { it.unfinished })
        Diag.log(
            TAG,
            "gathered ${items.size} item(s) from ${sources.size} source(s) in ${gathered.pages} page(s), " +
                "shorts=$includeShorts, unfinished=${gathered.unfinished.ifEmpty { "none" }}",
        )
        gathered
    }

    private class Walk(val items: List<MediaItem>, val pages: Int, val unfinished: String?)

    private suspend fun walk(
        source: MediaSource,
        part: SourcePart,
        loaded: AtomicInteger,
        progress: (Int) -> Unit,
    ): Walk {
        val items = mutableListOf<MediaItem>()
        val seen = mutableSetOf<PageToken>()
        var after: PageToken? = null
        var fetched = 0
        while (true) {
            if (fetched >= runawayPageLimit) return stop(source, part, items, fetched, "stopped at $fetched pages")
            val page = fetchWithRetries(source, part, after)
                ?: return stop(source, part, items, fetched, "page ${fetched + 1} failed $attemptsPerPage times")
            fetched++
            items += page.items
            progress(loaded.addAndGet(page.items.size))
            val next = page.next ?: break
            if (!seen.add(next)) return stop(source, part, items, fetched, "continuation repeated")
            after = next
        }
        Diag.log(TAG, "\"${source.title}\" $part: ${items.size} item(s) in $fetched page(s), complete")
        return Walk(items, fetched, null)
    }

    private suspend fun fetchWithRetries(source: MediaSource, part: SourcePart, after: PageToken?): Page<MediaItem>? {
        repeat(attemptsPerPage) { attempt ->
            pages.page(source, part, after)?.let { return it }
            val which = if (after == null) "first page" else "a later page"
            Diag.warn(TAG, "\"${source.title}\" $part $which failed (attempt ${attempt + 1} of $attemptsPerPage)")
        }
        return null
    }

    private fun stop(source: MediaSource, part: SourcePart, items: List<MediaItem>, pages: Int, why: String): Walk {
        Diag.warn(TAG, "\"${source.title}\" $part: keeping ${items.size} item(s) from $pages page(s) — $why")
        return Walk(items, pages, "${source.title} $part: $why")
    }

    private companion object {
        const val TAG = "shuffle"
    }
}
