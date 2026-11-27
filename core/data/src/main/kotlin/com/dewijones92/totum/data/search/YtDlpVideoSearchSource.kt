package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.common.formatViewCount
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine

/**
 * Video search through the extraction engine (yt-dlp `ytsearch`).
 *
 * `ytsearch` takes a count and returns that many — there is no continuation to follow —
 * so every answer is a final page. Asking for more simply yields nothing further.
 */
public class YtDlpVideoSearchSource(private val engine: YtDlpEngine) : SearchSource {

    override suspend fun search(query: SearchQuery, limit: Int, after: PageToken?): SearchOutcome {
        if (after != null) return SearchOutcome.Success(Page.empty())
        return when (val result = engine.searchVideos(query.value, limit)) {
            is VideoSearchResult.Failure -> SearchOutcome.Failure(result.detail)
            is VideoSearchResult.Success -> SearchOutcome.Success(
                Page.last(
                    result.entries.map { entry ->
                        SearchHit.Video(
                            title = entry.title,
                            subtitle = entry.uploader,
                            artworkUrl = entry.thumbnailUrl?.let(HttpUrl::parse),
                            watchUrl = entry.watchUrl,
                            durationSeconds = entry.durationSeconds,
                            viewsText = entry.viewCount?.let(::formatViewCount),
                            membersOnly = entry.membersOnly,
                        )
                    },
                ),
            )
        }
    }
}
