package com.dewijones92.totum.innertube.comments

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed

/**
 * Reads a video's comments over two unauthenticated WEB `next` calls: fetch
 * the watch page to get the comment section's continuation token, then follow
 * it and parse. No token means comments are turned off for that video.
 */
public class HttpYouTubeComments(
    private val innerTube: InnerTubeClient,
) : YouTubeComments {

    override suspend fun forVideo(videoId: String): CommentsResult {
        val found = when (val result = innerTube.next(videoId)) {
            is InnerTubeResponse.Success -> result.parsed { CommentsResponseParser.findCommentsContinuation(it) }
            InnerTubeResponse.Unauthorized -> return CommentsResult.Failure("Unauthorized")
            is InnerTubeResponse.Failure -> return CommentsResult.Failure(result.detail)
        }
        val continuation = found ?: return CommentsResult.Disabled
        return when (val result = innerTube.nextContinuation(continuation)) {
            is InnerTubeResponse.Success -> result.parsed { CommentsResponseParser.parseComments(it) }
            InnerTubeResponse.Unauthorized -> CommentsResult.Failure("Unauthorized")
            is InnerTubeResponse.Failure -> CommentsResult.Failure(result.detail)
        }
    }

    override suspend fun repliesFor(token: String): RepliesResult =
        when (val result = innerTube.nextContinuation(token)) {
            is InnerTubeResponse.Success -> result.parsed { CommentsResponseParser.parseReplies(it) }
            InnerTubeResponse.Unauthorized -> RepliesResult.Failure("Unauthorized")
            is InnerTubeResponse.Failure -> RepliesResult.Failure(result.detail)
        }
}
