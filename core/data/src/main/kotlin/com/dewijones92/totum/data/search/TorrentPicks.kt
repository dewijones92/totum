package com.dewijones92.totum.data.search

public object TorrentPicks {
    public val RESOLUTIONS: List<String> = listOf("4K", "1080p", "720p", "SD")

    public fun filtered(
        hits: List<SearchHit.Torrent>,
        resolution: String?,
        trustedOnly: Boolean,
    ): List<SearchHit.Torrent> = hits.filter { hit ->
        (resolution == null || hit.resolution == resolution) && (!trustedOnly || hit.trusted)
    }

    public fun best(hits: List<SearchHit.Torrent>): SearchHit.Torrent? = hits
        .filter { !it.flagged && it.seeders > 0 }
        .maxWithOrNull(compareBy<SearchHit.Torrent> { score(it) }.thenBy { it.seeders })

    private fun score(hit: SearchHit.Torrent): Int {
        val picture = when (hit.resolution) {
            "1080p" -> FULL_HD
            "4K", "720p" -> GOOD
            null -> UNKNOWN
            else -> LOW
        }
        val trust = if (hit.trusted) TRUSTED_BONUS else 0
        val health = hit.seeders.coerceAtMost(SEEDERS_CAP) / SEEDERS_STEP
        return picture * PICTURE_WEIGHT + trust + health
    }

    private const val FULL_HD = 4
    private const val GOOD = 3
    private const val UNKNOWN = 2
    private const val LOW = 1
    private const val PICTURE_WEIGHT = 10
    private const val TRUSTED_BONUS = 15
    private const val SEEDERS_CAP = 200
    private const val SEEDERS_STEP = 20
}
