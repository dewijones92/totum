package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.discover.DiscoverTaste
import com.dewijones92.totum.data.discover.TasteMark
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SEVERAL_ARTISTS
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.counts

data class FollowedArtists(val ids: Set<String> = emptySet(), val names: Set<String> = emptySet())

class DiscoverTasteSource(
    private val history: suspend () -> List<PlayableItem>,
    private val starred: suspend () -> List<PlayableItem>,
    private val listens: suspend () -> List<StatEvent>,
    private val recentActions: () -> List<Pin>,
    private val followed: () -> FollowedArtists,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var kept: DiscoverTaste? = null
    private var keptAtMs = 0L

    suspend fun taste(): DiscoverTaste {
        kept?.takeIf { clock() - keptAtMs < TTL_MS }?.let { return it }
        val now = clock()
        val played = history().map { it.item }.filter { it.isMusic() }.flatMap { it.marks("played recently") }
        val heard = listens().filterIsInstance<StatEvent.Listen>()
            .filter { it.kind == ListenKind.MUSIC && it.counts && now - it.at <= LISTENED_WITHIN_MS }
            .flatMap { listen -> listen.author.names().map { TasteMark(null, it, "listened to in the last 14 days") } }
        val liked = starred().takeLast(STARRED_LOOKED_AT).map { it.item }.filter { it.isMusic() }
            .flatMap { it.marks("starred") }
        val inRecently = recentActions().flatMap { it.marks() }
        val follows = followed()
        val taste = DiscoverTaste(
            recent = played + heard + liked + inRecently,
            followed = follows.ids.map { TasteMark(it, null, "you follow them") } +
                follows.names.map { TasteMark(null, it, "you follow them") },
        )
        Diag.log(
            TAG,
            "taste read: ${played.size} played, ${heard.size} listened, ${liked.size} starred, " +
                "${inRecently.size} in Recently, followed ${follows.ids.size} id(s) and ${follows.names.size} name(s)",
        )
        kept = taste
        keptAtMs = now
        return taste
    }

    private fun MediaItem.isMusic() = contentKind == MediaContentKind.MUSIC

    private fun MediaItem.marks(why: String): List<TasteMark> =
        listOfNotNull(sourceUrl?.youTubeChannelId?.let { TasteMark(it, null, why) }) +
            author.names().map { TasteMark(null, it, why) }

    private fun Pin.marks(): List<TasteMark> = when (this) {
        is Pin.Artist -> listOf(TasteMark(browseId, name, "in Recently"))
        is Pin.ArtistSongs -> listOf(TasteMark(artistId, artist, "in Recently"))
        is Pin.Discover -> listOf(TasteMark(artistId, artistName, "in Recently"))
        else -> emptyList()
    }

    private fun String?.names(): List<String> =
        this?.split(SEVERAL_ARTISTS)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        const val TAG = "discover"
        const val TTL_MS = 5L * 60 * 1000
        const val LISTENED_WITHIN_MS = 14L * 24 * 60 * 60 * 1000
        const val STARRED_LOOKED_AT = 40
    }
}
