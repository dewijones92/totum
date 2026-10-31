package com.dewijones92.totum.data.torrent

internal fun withSharedPosters(results: List<TorrentResult>): List<TorrentResult> {
    val byWork = results
        .filter { it.artworkUrl != null }
        .groupBy { ReleaseName(it.title).workKey }
        .filterKeys { it != null }
        .mapValues { (_, found) -> found.first() }
    return results.map { result ->
        if (result.artworkUrl != null) return@map result
        val donor = byWork[ReleaseName(result.title).workKey] ?: return@map result
        result.copy(posterUrl = donor.posterUrl, imdbId = donor.imdbId)
    }
}
