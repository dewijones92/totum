package com.dewijones92.totum.music

import com.dewijones92.totum.common.Busy
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.showing
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.SEVERAL_ARTISTS
import com.dewijones92.totum.ui.common.artistPage
import com.dewijones92.totum.ui.common.artistsPage

class ArtistCredits(
    private val catalogue: YouTubeMusicCatalogue,
    private val operations: Operations? = null,
    private val finding: (String) -> String = { it },
) {

    fun needsAsking(item: MediaItem): Boolean = item.author?.let(SEVERAL_ARTISTS::containsMatchIn) == true

    suspend fun pageFor(item: MediaItem): MusicPage? {
        val single = item.artistPage() ?: return null
        if (!needsAsking(item)) return single
        val id = item.id.value
        val answer = operations.showing("artists:$id", finding(item.title)) {
            Busy.during("artists of $id") { catalogue.radio(RadioSeed.forSong(id), null) }
        }
        val song = (answer as? MusicResult.Success)?.value?.songs?.firstOrNull { it.videoId == id }
        val page = artistsPage(song?.artists.orEmpty(), item.title, single)
        val credited = song?.artists?.map { it.name } ?: answer
        Diag.log("nav", "artists of $id \"${item.author}\": YouTube Music credits $credited -> ${page?.label}")
        return page
    }
}
