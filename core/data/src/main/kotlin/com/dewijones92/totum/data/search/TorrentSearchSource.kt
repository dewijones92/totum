package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.data.torrent.TorrentResult
import com.dewijones92.totum.data.torrent.TorrentSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The home server's torrents, as ordinary search results.
 *
 * An adapter and nothing more: it exists so torrents arrive through the SAME seam as podcasts
 * and videos, rather than as a fourth thing the search screen has to know about. That is the
 * project's first law doing its job — the whole feature reaches the UI as one more `SearchHit`.
 *
 * Answers in one shot, like the iTunes directory: Prowlarr aggregates its indexers and returns
 * everything at once, so [Page.last] is a complete answer rather than a special case.
 */
public class TorrentSearchSource(
    private val server: HomeTorrentServer,
) : SearchSource {

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome {
        // No paging to continue: a second page would repeat the first, so an empty one is the
        // honest answer rather than searching the same thing again.
        if (after != null) return SearchOutcome.Success(Page.last(emptyList()))
        return server.search(query.value).asOutcome(limit)
    }

    override fun updates(query: SearchQuery, limit: Int): Flow<SearchOutcome> =
        server.searchUpdates(query.value).map { it.asOutcome(limit) }

    private fun TorrentSearchResult.asOutcome(limit: Int): SearchOutcome = when (this) {
        is TorrentSearchResult.Failure -> SearchOutcome.Failure(detail, slow)
        is TorrentSearchResult.Success -> SearchOutcome.Success(Page.last(results.toHits(limit)), stillWaitingFor)
    }

    /**
     * Best-seeded first, HERE rather than in the HTTP client.
     *
     * With twenty copies of one film the seeder count is the whole basis for choosing, and a
     * result with none will never play however good its name looks. Ordering is a presentation
     * decision, so it lives with the other presentation decisions instead of being re-derived by
     * every implementation of the port.
     */
    private fun List<TorrentResult>.toHits(limit: Int): List<SearchHit> =
        sortedByDescending { it.seeders }
            .take(limit)
            .map { torrent ->
                SearchHit.Torrent(
                    title = torrent.title,
                    subtitle = torrent.describe(),
                    artworkUrl = null,
                    magnet = torrent.magnet,
                    seeders = torrent.seeders,
                    sizeBytes = torrent.sizeBytes,
                    indexer = torrent.indexer,
                )
            }

    /** Seeders and size ARE the decision, so they are the subtitle rather than a detail view. */
    private fun TorrentResult.describe(): String =
        "$seeders seeders · ${"%.2f GB".format(sizeBytes / BYTES_PER_GB)}" +
            (indexer?.let { " · $it" } ?: "")

    private companion object {
        const val BYTES_PER_GB = 1_000_000_000.0
    }
}
