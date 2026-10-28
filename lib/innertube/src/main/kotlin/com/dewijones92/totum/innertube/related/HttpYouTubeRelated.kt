package com.dewijones92.totum.innertube.related

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed

/**
 * Reads a video's related / "up next" list from one unauthenticated WEB `next`
 * call — the same watch-page request that backs comments — and parses the
 * video lockups out of it.
 */
public class HttpYouTubeRelated(
    private val innerTube: InnerTubeClient,
) : YouTubeRelated {

    override suspend fun relatedTo(videoId: String): RelatedResult =
        when (val result = innerTube.next(videoId)) {
            is InnerTubeResponse.Success -> result.parsed { RelatedVideosParser.parse(it) }
            InnerTubeResponse.Unauthorized -> RelatedResult.Failure("Unauthorized")
            is InnerTubeResponse.Failure -> RelatedResult.Failure(result.detail)
        }
}
