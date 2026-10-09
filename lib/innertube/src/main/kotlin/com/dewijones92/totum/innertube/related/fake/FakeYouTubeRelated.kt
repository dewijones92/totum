package com.dewijones92.totum.innertube.related.fake

import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.innertube.related.YouTubeRelated

/** Scriptable [YouTubeRelated] for tests and previews; no network. */
public class FakeYouTubeRelated(
    public var result: RelatedResult = RelatedResult.Success(emptyList()),
) : YouTubeRelated {
    public val requests: MutableList<String> = mutableListOf()

    override suspend fun relatedTo(videoId: String): RelatedResult {
        requests += videoId
        return result
    }
}
