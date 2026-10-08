package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.withSolvedN

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
) : InnerTubePlayerStreams.AccountPlayer {

    override suspend fun playerFor(videoId: String): PlayerResult? {
        // Signed out is the ordinary case, not an error: the anonymous failure already said
        // why the video would not play, and that message is the better one to keep.
        val token = (
            runCatching { youTubeAccount.accessToken() }.getOrNull()
                as? AccessTokenResult.Available
            )?.token ?: return null
        val stamp = runCatching { signatureTimestamps.current() }.getOrNull() ?: return null
        // DOWNGRADED first, and that ordering is measured. Both clients answer OK for the
        // same rated video, but the current one withholds all but ONE stream (SABR) while
        // the downgraded one returns seven — so preferring the current client here means
        // reaching an age-restricted video and then watching it at 360p. This path only runs
        // when the anonymous attempt already failed, so there is no ordinary video to lose.
        return withPlayableStreams(videoId, "downgraded TV") {
            innerTubeClient.playerDowngradedTv(videoId, stamp, token)
        } ?: withPlayableStreams(videoId, "TV") { innerTubeClient.playerAsAccount(videoId, stamp, token) }
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
     * So success is defined as **a format we can fetch**, never as a status. Both attempts run
     * through here for that reason: the same trap catches the first one too, since its lone URL
     * carries an unsolved `n` and would 403 on playback.
     */
    private suspend fun withPlayableStreams(
        videoId: String,
        client: String,
        request: suspend () -> InnerTubeResponse,
    ): PlayerResult.Success? {
        val response = runCatching { request() }.getOrNull()
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
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
        val playable = parsed.streaming.withSolvedN(nSolver, playerUrl)
        // DELIBERATELY still `directlyPlayable`, not `playableSomehow`. The sibling gate in
        // InnerTubePlayerStreams was widened to accept a SABR-only response; this one must not be.
        // Its whole job is to keep trying CLIENTS, and the current TV client answers OK for an
        // age-restricted video while withholding the streams -- accepting that as success is what
        // short-circuited the downgraded client that returns seven fetchable URLs, and shipped a
        // feature that resolved and then would not play (2026-08-01).
        if (playable.directlyPlayable.isEmpty()) {
            Diag.log("resolve", "$videoId as $client -> OK but nothing fetchable; trying the next client")
            return null
        }
        Diag.log("resolve", "$videoId as $client -> ${playable.directlyPlayable.size} fetchable format(s)")
        return parsed.copy(streaming = playable)
    }
}
