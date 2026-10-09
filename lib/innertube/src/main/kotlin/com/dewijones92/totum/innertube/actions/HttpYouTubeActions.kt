package com.dewijones92.totum.innertube.actions

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.music.firstObject
import com.dewijones92.totum.innertube.music.parseMusicRoot
import com.dewijones92.totum.innertube.music.str

/**
 * Write actions against InnerTube as the authenticated TV client. Endpoints
 * verified to work with just the target id (like/subscribe need no per-item
 * params token); comment posting uses a locally-built [CreateCommentParams].
 * JSON string values are escaped so comment text can't break the body.
 */
public class HttpYouTubeActions(
    private val account: YouTubeAccount,
    private val innerTube: InnerTubeClient,
    private val endpoints: ActionEndpoints = ActionEndpoints(),
) : YouTubeActions {

    /** The write endpoints; overridable so tests can point at a mock server. */
    public data class ActionEndpoints(
        val subscribe: String = InnerTubeClient.SUBSCRIBE_URL,
        val unsubscribe: String = InnerTubeClient.UNSUBSCRIBE_URL,
        val like: String = InnerTubeClient.LIKE_URL,
        val dislike: String = InnerTubeClient.DISLIKE_URL,
        val removeLike: String = InnerTubeClient.REMOVE_LIKE_URL,
        val createComment: String = InnerTubeClient.CREATE_COMMENT_URL,
        val editPlaylist: String = InnerTubeClient.EDIT_PLAYLIST_URL,
        val next: String = InnerTubeClient.NEXT_URL,
    )

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): ActionResult {
        val url = if (subscribed) endpoints.subscribe else endpoints.unsubscribe
        return act(url) { """"channelIds":["${escape(channelId)}"]""" }
    }

    override suspend fun setRating(videoId: String, rating: VideoRating): ActionResult {
        val url = when (rating) {
            VideoRating.LIKE -> endpoints.like
            VideoRating.DISLIKE -> endpoints.dislike
            VideoRating.NONE -> endpoints.removeLike
        }
        return act(url) { """"target":{"videoId":"${escape(videoId)}"}""" }
    }

    override suspend fun rating(videoId: String): VideoRating? {
        val token = (account.accessToken() as? AccessTokenResult.Available)?.token ?: return null
        val answer = innerTube.action(endpoints.next, """"videoId":"${escape(videoId)}"""", token)
        val body = (answer as? InnerTubeResponse.Success)?.body ?: return null
        val root = parseMusicRoot(body) ?: return null
        val status = root.firstObject("likeButtonRenderer")?.str("likeStatus")
            ?: root.firstObject("likeStatusEntity")?.str("likeStatus")
        return when (status) {
            "LIKE" -> VideoRating.LIKE
            "DISLIKE" -> VideoRating.DISLIKE
            "INDIFFERENT" -> VideoRating.NONE
            else -> null
        }
    }

    override suspend fun setSavedToWatchLater(videoId: String, saved: Boolean): ActionResult {
        val action = if (saved) {
            """{"action":"ACTION_ADD_VIDEO","addedVideoId":"${escape(videoId)}"}"""
        } else {
            """{"action":"ACTION_REMOVE_VIDEO","removedVideoId":"${escape(videoId)}"}"""
        }
        return act(endpoints.editPlaylist) { """"playlistId":"WL","actions":[$action]""" }
    }

    override suspend fun postComment(videoId: String, text: String): ActionResult =
        act(endpoints.createComment) {
            """"createCommentParams":"${CreateCommentParams.forVideo(videoId)}","commentText":"${escape(text)}""""
        }

    private suspend fun act(url: String, fields: () -> String): ActionResult {
        val token = when (val result = account.accessToken()) {
            is AccessTokenResult.Available -> result.token
            AccessTokenResult.SignedOut -> return ActionResult.SignedOut
            is AccessTokenResult.Failure -> return ActionResult.Failure(result.detail)
        }
        return perform(url, fields(), token)
    }

    private suspend fun perform(url: String, fields: String, token: AccessToken): ActionResult =
        when (val response = innerTube.action(url, fields, token)) {
            is InnerTubeResponse.Success -> response.body.actionOutcome()
            InnerTubeResponse.Unauthorized -> ActionResult.SignedOut
            is InnerTubeResponse.Failure -> ActionResult.Failure(response.detail)
        }

    /**
     * Whether InnerTube actually did the thing, as opposed to merely answering.
     *
     * A rejected edit comes back as **HTTP 200** with the refusal in the body, so treating any 2xx
     * as success was reporting work that never happened. Proven end to end on 2026-07-29: adding a
     * video to Watch Later logged `-> Success` and the video was not in Watch Later afterwards,
     * confirmed by reading the list back and refreshing it.
     *
     * Conservative on purpose. Only an explicit refusal is treated as failure — a body with no
     * status field still counts as success, because the action endpoints do not all report one and
     * demanding it would break like, subscribe and the rest for no evidence.
     */
    private fun String.actionOutcome(): ActionResult = when {
        contains(STATUS_FAILED) -> ActionResult.Failure(STATUS_FAILED)
        contains(ERROR_OBJECT) -> ActionResult.Failure(errorReason() ?: ERROR_OBJECT)
        else -> ActionResult.Success
    }

    /** The human-readable half of an InnerTube error, so the log says WHY rather than just "failed". */
    private fun String.errorReason(): String? =
        Regex(""""message"\s*:\s*"([^"]{1,160})"""").find(this)?.groupValues?.getOrNull(1)

    private companion object {
        const val STATUS_FAILED = "STATUS_FAILED"
        const val ERROR_OBJECT = "\"error\""
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
