package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.SignatureSolver
import com.dewijones92.totum.innertube.player.WatchNextDetails
import com.dewijones92.totum.innertube.player.WatchNextParser
import com.dewijones92.totum.innertube.player.withSolvedChallenges

/**
 * Asks YouTube for a video as the signed-in account, for the ones it refuses anonymously.
 *
 * Returns null when signed out rather than throwing, so the anonymous path's failure stands
 * as the reason — "you are not signed in" is a different, better message than a token error.
 */
class SignedInTvPlayer(
    private val youTubeAccount: YouTubeAccount,
    private val signatureTimestamps: HttpSignatureTimestampSource,
    private val innerTubeClient: InnerTubeClient,
    private val nSolver: NSolver,
    private val signatureSolver: SignatureSolver,
) : InnerTubePlayerStreams.AccountPlayer {

    override suspend fun playerFor(videoId: String, describe: Boolean): PlayerResult? {
        // Signed out is the ordinary case, not an error: the anonymous failure already said
        // why the video would not play, and that message is the better one to keep.
        val token = (
            runCatching { youTubeAccount.accessToken() }.getOrNull()
                as? AccessTokenResult.Available
            )?.token ?: return null
        val stamp = runCatching { signatureTimestamps.current() }.getOrNull() ?: return null
        return withPlayableStreams(videoId, "downgraded TV", describe = {
            if (describe) {
                describedByTheWatchPage(videoId, token)
            } else {
                Diag.log("resolve", "$videoId: its row already has a title; the watch page is not asked")
                WatchNextDetails(title = videoId, author = null, channelId = null)
            }
        }) {
            innerTubeClient.playerDowngradedTv(videoId, stamp, token)
        }
    }

    private suspend fun describedByTheWatchPage(videoId: String, token: AccessToken): WatchNextDetails? {
        val response = runCatching { innerTubeClient.nextAsAccount(videoId, token) }.getOrNull()
        val details = (response as? InnerTubeResponse.Success)?.body?.let { WatchNextParser.details(it, videoId) }
        Diag.log(
            "resolve",
            if (details != null) {
                "$videoId described by the account's watch page: \"${details.title}\" by ${details.author}"
            } else {
                "$videoId: the account's watch page gave no title (${response.toString().take(
                    LOGGED_RESPONSE_CHARS
                )}); it shows as its id"
            },
        )
        return details
    }

    /**
     * A player response ONLY if something in it can actually be fetched, with `n` solved.
     *
     * The distinction this draws is the one that matters, and getting it wrong shipped a feature
     * that did nothing (caught on an emulator 2026-08-01, not by any test). YouTube answers
     * `status=OK` for an age-restricted video on the CURRENT TV client — while withholding the
     * streams, one SABR-degraded URL out of seven. Treating that OK as success short-circuited
     * the downgraded client that would have worked, so the video "resolved" and then refused to
     * play, which reads to a user exactly like the feature not existing.
     *
     * So success is defined as **a format we can fetch**, never as a status. A URL with an unsolved
     * `n` would 403 on playback too.
     */
    private suspend fun withPlayableStreams(
        videoId: String,
        client: String,
        describe: suspend () -> WatchNextDetails?,
        request: suspend () -> InnerTubeResponse,
    ): PlayerResult.Success? {
        val response = runCatching { request() }.getOrNull()
        val body = (response as? InnerTubeResponse.Success)?.body
        val parsed = body?.let(PlayerResponseParser::parse)
        if (parsed !is PlayerResult.Success) {
            // WHAT it said, not just that it failed. Concluding "refused" from a null was the
            // mistake that made age restriction look impossible for two rounds.
            Diag.log("resolve", "$videoId as $client -> ${parsed ?: response}")
            return null
        }
        val playerUrl = runCatching { signatureTimestamps.playerScriptUrl() }.getOrNull() ?: run {
            Diag.warn("resolve", "$videoId resolved but no player script to solve its n parameters")
            return null
        }
        val playable = parsed.streaming.withSolvedChallenges(nSolver, signatureSolver, playerUrl)
        // DELIBERATELY still `directlyPlayable`, not `playableSomehow`. The sibling gate in
        // InnerTubePlayerStreams was widened to accept a SABR-only response; this one must not be.
        // Its whole job is to keep trying CLIENTS, and the current TV client answers OK for an
        // age-restricted video while withholding the streams -- accepting that as success is what
        // short-circuited the downgraded client that returns seven fetchable URLs, and shipped a
        // feature that resolved and then would not play (2026-08-01).
        if (playable.directlyPlayable.isEmpty()) {
            Diag.log(
                "resolve",
                "$videoId as $client -> OK but nothing fetchable; no signed-in answer " +
                    "(the tracking client's links all 403, ADR 49)",
            )
            return null
        }
        Diag.log("resolve", "$videoId as $client -> ${playable.directlyPlayable.size} fetchable format(s)")
        val details = parsed.details ?: describe()?.let { described ->
            (PlayerResponseParser.parse(body, described) as? PlayerResult.Success)?.details
        }
        return parsed.copy(streaming = playable, details = details)
    }
}

private const val LOGGED_RESPONSE_CHARS = 300
