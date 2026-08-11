package com.dewijones92.totum.ui.search

import com.dewijones92.totum.data.search.SearchSource

/**
 * The catalogues search asks, one per section.
 *
 * Grouped because they always travel together and because it makes the shape of the screen
 * explicit: one seam per catalogue, each answering independently, none of them knowing about the
 * others. Torrents are deliberately NOT here — they are nullable, since a search with no home
 * server has no torrent section to wait for at all.
 */
data class SearchSources(
    val podcasts: SearchSource,
    val videos: SearchSource,
    /** YouTube Music. Its own section because a song is not a video result. */
    val music: SearchSource,
)
