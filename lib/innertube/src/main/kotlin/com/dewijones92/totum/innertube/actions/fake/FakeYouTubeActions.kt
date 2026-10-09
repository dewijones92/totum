package com.dewijones92.totum.innertube.actions.fake

import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.YouTubeActions

/** Records calls and returns a scripted result; for tests and previews. */
public class FakeYouTubeActions(
    public var result: ActionResult = ActionResult.Success,
    public var ratings: Map<String, VideoRating> = emptyMap(),
) : YouTubeActions {

    override suspend fun rating(videoId: String): VideoRating? = ratings[videoId]

    public val subscribeCalls: MutableList<Pair<String, Boolean>> = mutableListOf()
    public val ratingCalls: MutableList<Pair<String, VideoRating>> = mutableListOf()
    public val watchLaterCalls: MutableList<Pair<String, Boolean>> = mutableListOf()
    public val commentCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): ActionResult {
        subscribeCalls += channelId to subscribed
        return result
    }

    override suspend fun setRating(videoId: String, rating: VideoRating): ActionResult {
        ratingCalls += videoId to rating
        return result
    }

    override suspend fun setSavedToWatchLater(videoId: String, saved: Boolean): ActionResult {
        watchLaterCalls += videoId to saved
        return result
    }

    override suspend fun postComment(videoId: String, text: String): ActionResult {
        commentCalls += videoId to text
        return result
    }
}
