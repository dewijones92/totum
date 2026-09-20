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
import com.dewijones92.totum.innertube.player.Refusal
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

    override suspend fun forgetSessions() {
        if (sessions.isEmpty()) return
        Diag.log("yt-sync", "dropping ${sessions.size} tracking session(s) — they belonged to the old account")
        sessions.clear()
    }

    override suspend fun beginSession(videoId: String): SessionResult {
        // Keep an existing session (and its cpn) if we already have one for this video.
        if (sessions[videoId] != null) return SessionResult.Opened
        return when (val found = fetchTracking(videoId)) {
            is Tracking.None -> found.why
            is Tracking.Found -> {
                sessions[videoId] = Session(found.tracking, newNonce())
                Diag.log("yt-sync", "$videoId tracking acquired for the account")
                SessionResult.Opened
            }
        }
    }

    /** Either this video's tracking URLs or the reason there are none — never both, never neither. */
    private sealed interface Tracking {
        data class Found(val tracking: PlaybackTracking) : Tracking
        data class None(val why: SessionResult) : Tracking
    }

    /** The tracking URLs for [videoId], or the [SessionResult] explaining why there are none. */
    private suspend fun fetchTracking(videoId: String): Tracking {
        val token = (account.accessToken() as? AccessTokenResult.Available)?.token ?: run {
            Diag.log("yt-sync", "$videoId not tracked: signed out")
            return Tracking.None(SessionResult.Unavailable("signed out"))
        }
        val timestamp = signatureTimestamps.current() ?: run {
            Diag.log("yt-sync", "$videoId not tracked: no player signature timestamp")
            return Tracking.None(SessionResult.Unavailable("no player signature timestamp"))
        }
        val response = innerTube.playerTracking(videoId, timestamp, token)
        if (response !is InnerTubeResponse.Success) {
            Diag.warn("yt-sync", "$videoId tracking request failed: $response")
            return Tracking.None(SessionResult.Unavailable("tracking request failed: $response"))
        }
        return trackingIn(videoId, response.body)
    }

    /**
     * The tracking in a `/player` body, or WHOSE fault it is that there is none.
     *
     * A refusal (`UNPLAYABLE`, `LOGIN_REQUIRED`) comes back as HTTP 200, and a stale signature
     * timestamp produces exactly that for every video at once — so reading it as a per-video
     * verdict would let the outbox write off its whole contents. A genuinely untrackable video
     * answers `OK` and simply has no tracking block.
     */
    private fun trackingIn(videoId: String, body: String): Tracking {
        PlaybackTrackingParser.parse(body)?.let { return Tracking.Found(it) }
        return when (val refusal = PlaybackTrackingParser.refusalReason(body)) {
            null -> {
                Diag.warn("yt-sync", "$videoId carried no playback tracking; this one video won't sync")
                Tracking.None(SessionResult.NotTrackable)
            }
            is Refusal.ThisVideo -> {
                Diag.warn("yt-sync", "$videoId: ${refusal.said} — this one video, and only it, won't sync")
                Tracking.None(SessionResult.NotTrackable)
            }
            is Refusal.MaybeUsAll -> {
                Diag.warn("yt-sync", "$videoId refused: ${refusal.said} — that may be this client, not the video")
                Tracking.None(SessionResult.Unavailable(refusal.said))
            }
        }
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
