package com.dewijones92.totum.innertube.history

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.BrowseTarget
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.feeds.VideoTileParser
import com.dewijones92.totum.innertube.player.PlaybackTracking
import com.dewijones92.totum.innertube.player.PlaybackTrackingParser
import com.dewijones92.totum.innertube.player.SignatureTimestampSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.random.Random

/**
 * Pings YouTube's `stats/playback` + `stats/watchtime` (the mechanism SmartTube uses) to
 * sync watch-progress to the account.
 *
 * The pings themselves were never the problem — it was **where they were sent**. The
 * tracking URLs used to come from the extractor's player response, and the extractor runs
 * unauthenticated, so they addressed an anonymous session: pinging them returned 204 and
 * changed nothing. Proven on 2026-07-31 by reading `FEhistory` back around a full playback,
 * finding it byte-identical, then repeating the same pings against a URL from an
 * authenticated `/player` call and watching the video appear at the top within twenty
 * seconds.
 *
 * So [beginSession] fetches its own URLs, as the signed-in TV client. That request needs
 * YouTube's current [SignatureTimestampSource] value or it is refused outright — see
 * [InnerTubeClient.playerTracking].
 */
public class HttpYouTubeWatchHistory(
    private val account: YouTubeAccount,
    private val client: OkHttpClient,
    private val innerTube: InnerTubeClient,
    private val signatureTimestamps: SignatureTimestampSource,
    private val newNonce: () -> String = ::randomClientPlaybackNonce,
) : YouTubeWatchHistory {

    private class Session(
        val tracking: PlaybackTracking,
        val cpn: String,
        var recordCreated: Boolean = false,
    )

    /**
     * The account's recent history, as watched percentages. TV identity, so [InnerTubeClient]
     * attaches the token itself — the inbound half is authenticated for the same reason the
     * outbound half is, and by the same rule.
     *
     * Every failure is an empty map rather than an exception: falling back to what this device
     * remembers is always a safe answer, and an item resuming from the local position is a far
     * smaller problem than a screen that will not open.
     */
    override suspend fun watchedPositions(): Map<String, AccountProgress> {
        val token = (account.accessToken() as? AccessTokenResult.Available)?.token ?: run {
            Diag.log("yt-sync", "not reading watched positions: signed out")
            return emptyMap()
        }
        val response = runCatching { innerTube.browse(BrowseTarget.Id(HISTORY_BROWSE_ID), token) }.getOrNull()
        val body = (response as? InnerTubeResponse.Success)?.body ?: run {
            Diag.warn("yt-sync", "could not read watched positions: $response")
            return emptyMap()
        }
        return VideoTileParser.watchedPositions(body).also {
            Diag.log("yt-sync", "YouTube knows a watched position for ${it.size} recent video(s)")
        }
    }

    private val sessions = mutableMapOf<String, Session>()

    override suspend fun beginSession(videoId: String) {
        // Keep an existing session (and its cpn) if we already have one for this video.
        if (sessions[videoId] != null) return
        val token = (account.accessToken() as? AccessTokenResult.Available)?.token ?: run {
            Diag.log("yt-sync", "$videoId not tracked: signed out")
            return
        }
        val timestamp = signatureTimestamps.current() ?: run {
            Diag.log("yt-sync", "$videoId not tracked: no player signature timestamp")
            return
        }
        val tracking = when (val response = innerTube.playerTracking(videoId, timestamp, token)) {
            is InnerTubeResponse.Success -> PlaybackTrackingParser.parse(response.body)
                ?: null.also { Diag.warn("yt-sync", "$videoId carried no playback tracking; progress won't sync") }
            else -> null.also { Diag.warn("yt-sync", "$videoId tracking request failed: $response") }
        } ?: return
        sessions[videoId] = Session(tracking, newNonce())
        Diag.log("yt-sync", "$videoId tracking acquired for the account")
    }

    override suspend fun reportProgress(
        videoId: String,
        positionSec: Float,
        lengthSec: Float,
        finished: Boolean,
    ): WatchHistoryResult {
        val session = sessions[videoId] ?: return WatchHistoryResult.NoSession
        return when (val token = account.accessToken()) {
            AccessTokenResult.SignedOut -> WatchHistoryResult.SignedOut
            is AccessTokenResult.Failure -> WatchHistoryResult.Failure(token.detail)
            is AccessTokenResult.Available -> report(session, positionSec, lengthSec, finished, token.token)
        }
    }

    private suspend fun report(
        session: Session,
        positionSec: Float,
        lengthSec: Float,
        finished: Boolean,
        token: AccessToken,
    ): WatchHistoryResult {
        val position = if (finished) lengthSec else positionSec
        val common = "&ver=2&cpn=${session.cpn}&cmt=$position" + if (finished) "&final=1" else ""

        // Open the record before watch-time updates land (SmartTube does the same).
        val playbackUrl = session.tracking.playbackUrl
        if (!session.recordCreated && playbackUrl != null) {
            val opened = ping(playbackUrl + common, token)
            if (opened != WatchHistoryResult.Success) return opened
            session.recordCreated = true
        }
        return ping(session.tracking.watchtimeUrl + common + "&st=$position&et=$position", token)
    }

    private suspend fun ping(url: String, token: AccessToken): WatchHistoryResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${token.value}")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> WatchHistoryResult.Success
                        response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                            WatchHistoryResult.SignedOut
                        else -> WatchHistoryResult.Failure("HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                WatchHistoryResult.Failure(e.message ?: "network error")
            }
        }

    private companion object {
        /** YouTube's own id for the account's watch history. */
        const val HISTORY_BROWSE_ID = "FEhistory"

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

private const val NONCE_LENGTH = 16
private const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** A client playback nonce: 16 chars of YouTube's cpn alphabet, like the web player. */
public fun randomClientPlaybackNonce(): String =
    buildString(NONCE_LENGTH) { repeat(NONCE_LENGTH) { append(NONCE_ALPHABET[Random.nextInt(NONCE_ALPHABET.length)]) } }
