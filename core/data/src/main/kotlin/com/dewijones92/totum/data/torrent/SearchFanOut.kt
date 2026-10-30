package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer.Companion.SLOW_INDEXERS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

internal class NamedSearch(val name: String, val ask: suspend (String) -> TorrentSearchResult)

internal class SearchFanOut(private val lateDeadlineMs: Long) {

    fun updates(
        query: String,
        listed: List<NamedSearch>?,
        listing: suspend () -> List<NamedSearch>,
        alsoAsk: List<NamedSearch>,
    ): Flow<TorrentSearchResult> = channelFlow {
        val placeholder = NamedSearch(HOME_SERVER) { TorrentSearchResult.Success(emptyList()) }
        val search = OneSearch(query, this, (listed ?: listOf(placeholder)) + alsoAsk)
        search.announce()
        val allAnswered = withTimeoutOrNull(lateDeadlineMs) {
            alsoAsk.forEach(search::ask)
            val home = listed ?: listing().also { search.replace(placeholder, it) }
            home.forEach(search::ask)
            search.joinAll()
        } != null
        if (!allAnswered) search.giveUp()
        search.log()
    }
}

private class OneSearch(
    private val query: String,
    private val out: ProducerScope<TorrentSearchResult>,
    first: List<NamedSearch>,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val known = first.toMutableList()
    private val answers = mutableMapOf<NamedSearch, Pair<TorrentSearchResult, Long>>()
    private val lock = Mutex()
    private val asks = mutableListOf<Job>()

    suspend fun announce() = lock.withLock { if (known.size > 1) out.send(combined(known, answers)) }

    fun ask(source: NamedSearch) {
        asks += out.launch {
            val one = answerOf(source)
            lock.withLock {
                answers[source] = one to started.elapsedNow().inWholeMilliseconds
                out.send(combined(known, answers))
            }
        }
    }

    suspend fun replace(placeholder: NamedSearch, sources: List<NamedSearch>) = lock.withLock {
        known.remove(placeholder)
        known.addAll(0, sources)
        if (known.size > 1) out.send(combined(known, answers))
    }

    suspend fun joinAll() = asks.joinAll()

    suspend fun giveUp() {
        asks.forEach { it.cancel() }
        val final = lock.withLock { combined(known, answers).withoutWaiting() }
        out.send(if (answers.isEmpty()) TorrentSearchResult.Failure(SLOW_INDEXERS, slow = true) else final)
    }

    suspend fun log() = lock.withLock {
        logEach()
        logTotal(combined(known, answers))
    }

    private suspend fun answerOf(source: NamedSearch): TorrentSearchResult =
        runCatching { source.ask(query) }.getOrElse { e ->
            if (e is CancellationException) throw e
            Diag.warn("torrent", "search \"$query\" (${source.name}) failed unexpectedly", e)
            TorrentSearchResult.Failure(e.message ?: e.javaClass.simpleName)
        }

    private fun logEach() {
        val each = answers.entries.sortedBy { it.value.second }.joinToString("; ") { (source, answer) ->
            val (outcome, ms) = answer
            "${source.name} ${ms}ms " + when (outcome) {
                is TorrentSearchResult.Success -> "${outcome.results.size} result(s)"
                is TorrentSearchResult.Failure -> "failed: ${outcome.detail}"
            }
        }
        val leftOut = known.filterNot { it in answers }.map { it.name }
        val waited = started.elapsedNow().inWholeMilliseconds
        val tail = if (leftOut.isEmpty()) "" else "; gave up after ${waited}ms on: $leftOut"
        val found = answers.values.sumOf { (it.first as? TorrentSearchResult.Success)?.results?.size ?: 0 }
        val kept = (combined(known, answers) as? TorrentSearchResult.Success)?.results?.size ?: found
        val merged = if (found > kept) "; ${found - kept} found by two sources listed once" else ""
        Diag.log("torrent", "search \"$query\" per indexer: $each$tail$merged")
    }

    private fun logTotal(result: TorrentSearchResult) {
        if (result !is TorrentSearchResult.Success) return
        Diag.log(
            "torrent",
            "search \"$query\" -> ${result.results.size} result(s) in " +
                "${started.elapsedNow().inWholeMilliseconds}ms " +
                "[top seeders=${result.results.maxOfOrNull { it.seeders } ?: "-"}]",
        )
    }
}

private fun combined(
    known: List<NamedSearch>,
    answers: Map<NamedSearch, Pair<TorrentSearchResult, Long>>,
): TorrentSearchResult {
    val found = known.mapNotNull { (answers[it]?.first as? TorrentSearchResult.Success)?.results }
    val waiting = known.filterNot { it in answers }.map { it.name }
    return when {
        found.isNotEmpty() -> TorrentSearchResult.Success(onePerTorrent(found.flatten()), waiting)
        waiting.isEmpty() -> answers.values.map { it.first }.firstOrNull { it is TorrentSearchResult.Failure }
            ?: TorrentSearchResult.Success(emptyList())
        else -> TorrentSearchResult.Success(emptyList(), waiting)
    }
}

private fun onePerTorrent(results: List<TorrentResult>): List<TorrentResult> {
    val best = linkedMapOf<String, TorrentResult>()
    results.forEach { result ->
        val key = when (val link = result.link) {
            is TorrentLink.Magnet -> INFO_HASH.find(link.uri)?.groupValues?.get(1)?.lowercase() ?: link.uri
            is TorrentLink.ViaIndexer -> link.path
        }
        best[key] = best[key]?.let { kept -> mergedFacts(kept, result) } ?: result
    }
    return best.values.toList()
}

private fun mergedFacts(a: TorrentResult, b: TorrentResult): TorrentResult {
    val (base, other) = if (b.seeders > a.seeders) b to a else a to b
    val standing = listOf(base, other).minBy { it.uploader.ordinal }
    val link = listOf(base, other).map {
        it.link
    }.firstOrNull { it is TorrentLink.Magnet && TRACKER in it.uri } ?: base.link
    return base.copy(
        link = link,
        uploader = standing.uploader,
        uploaderName = standing.uploaderName ?: base.uploaderName ?: other.uploaderName,
        leechers = base.leechers ?: other.leechers,
        publishedAt = base.publishedAt ?: other.publishedAt,
        fileCount = base.fileCount ?: other.fileCount,
    )
}

private fun TorrentSearchResult.withoutWaiting(): TorrentSearchResult =
    if (this is TorrentSearchResult.Success) copy(stillWaitingFor = emptyList()) else this

private const val HOME_SERVER = "the home server"
private val INFO_HASH = Regex("""btih:([0-9A-Fa-f]{40})""")
private const val TRACKER = "&tr="
