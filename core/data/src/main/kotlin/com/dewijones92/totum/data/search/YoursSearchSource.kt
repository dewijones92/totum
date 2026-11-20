package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomePiCall
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.home.playable
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.FuzzyMatch
import com.dewijones92.totum.domain.FuzzyQuery
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.searchableText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

public sealed interface HomeHoldings {
    public data class Read(val jobs: List<HomeJob>) : HomeHoldings
    public data object NotRead : HomeHoldings
}

public class YoursSearchSource(
    private val onPhone: suspend () -> List<DownloadedMedia>,
    private val atHome: (suspend () -> HomeHoldings)?,
    private val jellyfin: JellyfinApi?,
    private val networkTimeoutMs: Long = NETWORK_TIMEOUT_MS,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : SearchSource {

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome =
        updates(query, limit).last()

    override fun updates(query: SearchQuery, limit: Int): Flow<SearchOutcome> = channelFlow {
        val run = Run(query, limit, this)
        val phone = withContext(compute) { phoneMatches(query, FuzzyMatch.query(query.value)) }
        run.publish { this.phone = phone }
        atHome?.let { read -> launch { run.publish(homeChange(query, read)) } }
        jellyfin?.let { api -> launch { run.publish(jellyfinChange(query, api)) } }
    }

    private suspend fun phoneMatches(query: SearchQuery, parsed: FuzzyQuery): List<PlayableItem> {
        val all = onPhone()
        val matching = all.filter { FuzzyMatch.matches(parsed, FuzzyMatch.text(it.item.searchableText)) }
        Diag.log("search", "dewidebug yours \"${query.value}\": ${matching.size} of ${all.size} download(s) match")
        return matching.map { it.playable }
    }

    private suspend fun homeChange(query: SearchQuery, read: suspend () -> HomeHoldings): Parts.() -> Unit {
        val held = withTimeoutOrNull(networkTimeoutMs) { read() }
        val jobs = (held as? HomeHoldings.Read)?.jobs
        val done = jobs.orEmpty().filter { it.state == HomeJobState.DONE }
        val parsed = FuzzyMatch.query(query.value)
        val matches = jobs?.let {
            done.mapNotNull { it.item.toPlayable() }
                .filter { FuzzyMatch.matches(parsed, FuzzyMatch.text(it.item.searchableText)) }
        }
        val outcome = when {
            matches != null -> "${matches.size} of ${done.size} finished download(s) at home match"
            held == null -> "home's downloads not read: no answer in ${networkTimeoutMs}ms"
            else -> "home's downloads not read: the Pi did not answer"
        }
        Diag.log("search", "dewidebug yours \"${query.value}\": $outcome")
        return {
            matches?.let { home = it }
            waiting = waiting - HOME
            if (matches == null) addNote(HOME_UNREACHABLE)
        }
    }

    private suspend fun jellyfinChange(query: SearchQuery, api: JellyfinApi): Parts.() -> Unit {
        val answer = withTimeoutOrNull(networkTimeoutMs) { api.search(query.value) }
        val listing = (answer as? HomeAnswer.Ok)?.value
        val found = listing?.takeIf { it.search != null }?.items.orEmpty().filter { it.kind.playable }
        val note = noteFor(answer, listing)
        Diag.log("search", "dewidebug yours \"${query.value}\": jellyfin ${describe(answer, listing, found.size)}")
        return {
            jellyfin = found.map { it to api.playable(it) }
            waiting = waiting - JELLYFIN
            note?.let(::addNote)
        }
    }

    private fun noteFor(answer: HomeAnswer<JellyfinListing>?, listing: JellyfinListing?): String? = when {
        answer == null -> HOME_UNREACHABLE
        answer is HomeAnswer.No -> noteForRefusal(answer)
        listing?.search == null -> JELLYFIN_NEEDS_UPDATING
        else -> null
    }

    private fun noteForRefusal(answer: HomeAnswer.No): String? = when {
        answer.httpCode == null && answer.reason == HomePiCall.NOT_SIGNED_IN -> answer.reason
        answer.httpCode == null -> HOME_UNREACHABLE
        answer.httpCode == SERVICE_UNAVAILABLE -> null
        else -> answer.reason
    }

    private fun describe(answer: HomeAnswer<JellyfinListing>?, listing: JellyfinListing?, playable: Int): String =
        when {
            answer == null -> "did not answer in ${networkTimeoutMs}ms"
            answer is HomeAnswer.No -> "refused: ${answer.reason} (http ${answer.httpCode})"
            listing?.search == null -> "answered without the search echo, so the Pi has no search route yet"
            else -> "$playable playable of ${listing.items.size} returned (total ${listing.total})"
        }

    private inner class Run(
        private val query: SearchQuery,
        private val limit: Int,
        private val out: ProducerScope<SearchOutcome>,
    ) {
        private val started = System.currentTimeMillis()
        private val gate = Mutex()
        private val parts = Parts(
            waiting = listOfNotNull(HOME.takeIf { atHome != null }, JELLYFIN.takeIf { jellyfin != null }),
        )

        suspend fun publish(change: Parts.() -> Unit) = gate.withLock {
            parts.change()
            val merged = YoursMerge.merge(parts.phone, parts.home, parts.jellyfin)
            out.send(
                SearchOutcome.Success(
                    Page.last(merged.take(limit)),
                    stillWaitingFor = parts.waiting,
                    notes = parts.notes.toList(),
                ),
            )
            if (parts.waiting.isEmpty()) log(merged.size)
        }

        private fun log(rows: Int) {
            val took = System.currentTimeMillis() - started
            val shown = if (rows > limit) " (showing $limit)" else ""
            val notes = if (parts.notes.isEmpty()) "" else "; notes: ${parts.notes}"
            Diag.log(
                "search",
                "dewidebug yours \"${query.value}\" done after ${took}ms: phone=${parts.phone.size} " +
                    "home=${parts.home.size} jellyfin=${parts.jellyfin.size} -> $rows row(s)$shown$notes",
            )
        }
    }

    private class Parts(
        var phone: List<PlayableItem> = emptyList(),
        var home: List<PlayableItem> = emptyList(),
        var jellyfin: List<Pair<JellyfinItem, PlayableItem>> = emptyList(),
        var waiting: List<String>,
        val notes: MutableList<String> = mutableListOf(),
    ) {
        fun addNote(note: String) {
            if (note !in notes) notes += note
        }
    }

    public companion object {
        public const val HOME: String = "🏠 home"
        public const val JELLYFIN: String = "🎞️ Jellyfin"
        public const val HOME_UNREACHABLE: String = "🏠 Home not reachable"
        public const val JELLYFIN_NEEDS_UPDATING: String = "⚠️ The home server needs updating to search Jellyfin"
        private const val NETWORK_TIMEOUT_MS = 10_000L
        private const val SERVICE_UNAVAILABLE = 503
    }
}
