package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer.Companion.HTTP_GATEWAY_TIMEOUT
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer.Companion.HTTP_UNAUTHORIZED
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer.Companion.SLOW_INDEXERS
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer.Companion.TOKEN_HEADER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.TimeSource

internal class ProwlarrSearch(
    private val client: OkHttpClient,
    private val prowlarrBase: String,
    private val prowlarrApiKey: () -> String,
    private val token: () -> String,
    private val lateDeadlineMs: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var indexers: List<Indexer>? = null

    suspend fun search(query: String): TorrentSearchResult = updates(query).last()

    fun updates(query: String): Flow<TorrentSearchResult> = channelFlow {
        val started = TimeSource.Monotonic.markNow()
        val known = indexers ?: listIndexers()?.also { indexers = it }
        if (known.isNullOrEmpty()) {
            send(searchOne(query, null).also { logTotal(query, it, started) })
            return@channelFlow
        }
        val answers = mutableMapOf<Indexer, Pair<TorrentSearchResult, Long>>()
        val lock = Mutex()
        val asks = known.map { indexer ->
            launch {
                val one = searchOne(query, indexer)
                val (snapshot, everyone) = lock.withLock {
                    answers[indexer] = one to started.elapsedNow().inWholeMilliseconds
                    combined(known, answers) to (answers.size == known.size)
                }
                if (snapshot.worthShowing(everyone)) send(snapshot)
            }
        }
        val allAnswered = withTimeoutOrNull(lateDeadlineMs) { asks.joinAll() } != null
        if (!allAnswered) {
            asks.forEach { it.cancel() }
            val final = lock.withLock { combined(known, answers).withoutWaiting() }
            send(if (answers.isEmpty()) TorrentSearchResult.Failure(SLOW_INDEXERS, slow = true) else final)
        }
        lock.withLock { logIndexers(query, known, answers, started) }
        logTotal(query, lock.withLock { combined(known, answers) }, started)
    }

    private fun logIndexers(
        query: String,
        known: List<Indexer>,
        answers: Map<Indexer, Pair<TorrentSearchResult, Long>>,
        started: TimeSource.Monotonic.ValueTimeMark,
    ) {
        val each = answers.entries.sortedBy { it.value.second }.joinToString("; ") { (indexer, answer) ->
            val (outcome, ms) = answer
            "${indexer.name} ${ms}ms " + when (outcome) {
                is TorrentSearchResult.Success -> "${outcome.results.size} result(s)"
                is TorrentSearchResult.Failure -> "failed: ${outcome.detail}"
            }
        }
        val leftOut = known.filterNot { it in answers }.map { it.name }
        val waited = started.elapsedNow().inWholeMilliseconds
        val tail = if (leftOut.isEmpty()) "" else "; gave up after ${waited}ms on: $leftOut"
        Diag.log("torrent", "search \"$query\" per indexer: $each$tail")
    }

    private fun logTotal(query: String, result: TorrentSearchResult, started: TimeSource.Monotonic.ValueTimeMark) {
        if (result !is TorrentSearchResult.Success) return
        Diag.log(
            "torrent",
            "search \"$query\" -> ${result.results.size} result(s) in " +
                "${started.elapsedNow().inWholeMilliseconds}ms " +
                "[top seeders=${result.results.maxOfOrNull { it.seeders } ?: "-"}]",
        )
    }

    private suspend fun listIndexers(): List<Indexer>? {
        val request = Request.Builder()
            .url("$prowlarrBase/api/v1/indexer")
            .header("X-Api-Key", prowlarrApiKey())
            .header(TOKEN_HEADER, token())
            .build()
        val listed = try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) null else parseIndexers(response.body.string())
            }
        } catch (e: IOException) {
            Diag.warn("torrent", "could not list the home server's indexers; searching them all at once", e)
            null
        }
        Diag.log(
            "torrent",
            "indexers: ${listed?.joinToString { "${it.id} ${it.name}" } ?: "unknown, searching them all at once"}"
        )
        return listed
    }

    private fun parseIndexers(body: String): List<Indexer>? =
        (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.mapNotNull { element ->
            val indexer = element as? JsonObject ?: return@mapNotNull null
            val enabled = indexer["enable"]?.jsonPrimitive?.booleanOrNull ?: true
            val id = indexer["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            Indexer(id, indexer["name"]?.jsonPrimitive?.contentOrNull ?: "indexer $id").takeIf { enabled }
        }

    private suspend fun searchOne(query: String, indexer: Indexer?): TorrentSearchResult {
        val encoded = query.replace(" ", "+")
        val only = indexer?.let { "&indexerIds=${it.id}" }.orEmpty()
        val request = Request.Builder()
            .url("$prowlarrBase/api/v1/search?query=$encoded&type=search$only")
            .header("X-Api-Key", prowlarrApiKey())
            .header(TOKEN_HEADER, token())
            .build()
        val label = indexer?.name ?: "all indexers"
        return try {
            client.newCall(request).await().use { response -> read(response, query, label) }
        } catch (e: IOException) {
            // The Pi is only reachable at home or over wg-home, so this is the ordinary case of
            // being elsewhere rather than a fault. Said plainly so the UI can say it plainly.
            Diag.warn("torrent", "search for \"$query\" ($label) could not reach the home server", e)
            TorrentSearchResult.Failure(e.message ?: "could not reach the home server")
        }
    }

    private fun read(response: Response, query: String, label: String): TorrentSearchResult {
        if (!response.isSuccessful) {
            // Each code says something different to the person holding the phone, and
            // "HTTP 401" says nothing at all. A rejected token means the sign-in has to
            // be done again; a gateway timeout means the search itself was slow and
            // retrying may work; anything else is genuinely the server.
            val detail = when (response.code) {
                HTTP_UNAUTHORIZED -> "the home server rejected the sign-in — sign in again"
                HTTP_GATEWAY_TIMEOUT -> "the search took too long — try a narrower one"
                else -> "the home server answered HTTP ${response.code}"
            }
            Diag.warn("torrent", "search for \"$query\" ($label) failed: HTTP ${response.code} — $detail")
            return TorrentSearchResult.Failure(detail, slow = response.code == HTTP_GATEWAY_TIMEOUT)
        }
        val body = response.body.string()
        val results = parseProwlarr(body) ?: run {
            // A 200 that is not a search response means the request reached the wrong
            // thing — both services answer an unknown path with their own web UI rather
            // than a 404. Named precisely, because "0 results" and "you are talking to a
            // login page" look identical from the outside and have nothing in common.
            Diag.warn(
                "torrent",
                "search for \"$query\" ($label) got HTTP 200 but not a search response " +
                    "(${response.header("Content-Type")}, ${body.length} chars) — misrouted?",
            )
            return TorrentSearchResult.Failure("the home server returned an unreadable reply")
        }
        return TorrentSearchResult.Success(results)
    }
}

private data class Indexer(val id: Int, val name: String)

private fun combined(
    known: List<Indexer>,
    answers: Map<Indexer, Pair<TorrentSearchResult, Long>>,
): TorrentSearchResult {
    val found = answers.values.mapNotNull { (it.first as? TorrentSearchResult.Success)?.results }
    val waiting = known.filterNot { it in answers }.map { it.name }
    return when {
        found.isNotEmpty() -> TorrentSearchResult.Success(found.flatten(), waiting)
        waiting.isEmpty() -> answers.values.map { it.first }.firstOrNull { it is TorrentSearchResult.Failure }
            ?: TorrentSearchResult.Success(emptyList())
        else -> TorrentSearchResult.Success(emptyList(), waiting)
    }
}

private fun TorrentSearchResult.worthShowing(everyoneAnswered: Boolean): Boolean =
    everyoneAnswered || (this is TorrentSearchResult.Success && results.isNotEmpty())

private fun TorrentSearchResult.withoutWaiting(): TorrentSearchResult =
    if (this is TorrentSearchResult.Success) copy(stillWaitingFor = emptyList()) else this
