package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken

/**
 * One song YouTube Music returned.
 *
 * A song is **a YouTube video with music metadata**, which is the whole reason this is cheap: the
 * id is an ordinary `videoId`, so everything downstream — extraction, stream picking, the queue,
 * downloads, offline routing — is the machinery that already exists. What is new is only that
 * YouTube Music knows the artist, the album and the exact duration, where video search knows an
 * uploader and a view count.
 */
public data class MusicSong(
    public val videoId: String,
    public val title: String,
    /** The credited artist, as YouTube Music renders it; null when the row omits it. */
    public val artist: String?,
    /** The album, when the row names one. Singles and user uploads often do not. */
    public val album: String?,
    public val durationSeconds: Long?,
    public val thumbnailUrl: HttpUrl?,
    public val watchUrl: HttpUrl,
    /** "276M plays" as YouTube Music renders it; null when absent. */
    public val playsText: String? = null,
)

public sealed interface SearchSongsResult {
    public data class Success(public val page: Page<MusicSong>) : SearchSongsResult
    public data class Failure(public val detail: String) : SearchSongsResult
}

/**
 * Song search on YouTube Music (no sign-in).
 *
 * Separate from [com.dewijones92.totum.innertube.search.YouTubeSearch] because it is a different
 * *catalogue*, not a different transport: the same InnerTube API answers as the `WEB_REMIX`
 * client with music renderers, and the results are songs — titled, credited and album-tagged —
 * rather than whatever a channel happened to upload.
 */
public interface YouTubeMusicSearch {
    /** [after] continues a previous page; null starts a fresh search for [query]. */
    public suspend fun searchSongs(query: String, limit: Int, after: PageToken? = null): SearchSongsResult
}
