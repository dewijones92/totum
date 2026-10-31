package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.feeds.SeenProgress
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
        val success = response as? InnerTubeResponse.Success
        val details = success?.parsed { body ->
            Diag.log("resolve", "dewidebug $videoId watch page ${descriptionKeys(body)}")
            Diag.log("resolve", "dewidebug $videoId watch page dates ${dateSamples(body)}")
            Diag.log("resolve", "dewidebug $videoId watch page progress ${progressKeys(body)}")
            WatchNextParser.details(body, videoId)
        }
        Diag.log(
            "resolve",
            if (details != null) {
                "$videoId described by the account's watch page: \"${details.title}\" by ${details.author}, " +
                    "${details.description?.length ?: 0} chars of description, " +
                    "published ${details.publishedOn ?: "not said (${dateKeys(success.body)})"} " +
                    "(\"${details.publishedAgo ?: "no relative wording"}\")"
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
        val success = response as? InnerTubeResponse.Success
        val parsed = success?.parsed { body ->
            Diag.log("resolve", "dewidebug $videoId $client player response ${descriptionKeys(body)}")
            Diag.log("resolve", "dewidebug $videoId $client player response progress ${progressKeys(body)}")
            PlayerResponseParser.parse(body)
        }
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
        val details = parsed.details ?: run {
            val described = describe() ?: WatchNextDetails(title = videoId, author = null, channelId = null)
            (success?.parsed { PlayerResponseParser.parse(it, described) } as? PlayerResult.Success)?.details
        }
        accountStart(videoId, parsed.accountStartMs, details?.lengthSeconds)
        return parsed.copy(streaming = playable, details = details)
    }
}

private const val LOGGED_RESPONSE_CHARS = 300

private val DESCRIPTION_FIELDS = listOf(
    "shortDescription",
    "description",
    "attributedDescription",
    "descriptionBodyText",
    "attributedDescriptionBodyText",
)

private val DESCRIPTION_KEY = Regex(""""(${DESCRIPTION_FIELDS.joinToString("|")})"\s*:\s*(.{0,60})""")

internal fun dateSamples(body: String): String =
    DATE_SAMPLE.findAll(body).map { "${it.groupValues[1]}: ${it.groupValues[2]}" }.distinct().take(DATE_SAMPLES)
        .joinToString("; ").ifEmpty { "no date field" }

private val DATE_SAMPLE =
    Regex(""""(dateText|publishDate|uploadDate|publishedTimeText|relativeDateText)"\s*:\s*(.{0,60})""")
private const val DATE_SAMPLES = 6

internal fun dateKeys(body: String): String =
    DATE_KEY.findAll(body).map { it.groupValues[1] }.distinct().joinToString().ifEmpty { "no date field" }

private val DATE_KEY = Regex(""""(dateText|publishDate|uploadDate|publishedTimeText|relativeDateText)"""")

internal fun descriptionKeys(body: String): String {
    val hits = DESCRIPTION_KEY.findAll(body).toList()
    if (hits.isEmpty()) return "has no description field"
    return hits.groupBy { it.groupValues[1] }.entries.joinToString("; ") { (key, found) ->
        "$key x${found.size}: ${found.first().groupValues[2].replace('\n', ' ')}"
    }
}

private val PROGRESS_FIELDS = listOf(
    "percentDurationWatched",
    "startTimeSeconds",
    "startSeconds",
    "resume\\w*",
    "watchProgress\\w*",
    "lastWatched\\w*",
    "playbackPosition\\w*",
)

private val PROGRESS_KEY = Regex(""""(${PROGRESS_FIELDS.joinToString("|")})"\s*:\s*(.{0,40})""")

internal fun progressKeys(body: String): String {
    val hits = PROGRESS_KEY.findAll(body).toList()
    if (hits.isEmpty()) return "none"
    val at = body.indexOf("\"startSeconds\"")
    if (at >= 0) {
        Diag.log(
            "resolve",
            "dewidebug startSeconds context: ${body.substring(
                maxOf(0, at - CONTEXT_CHARS),
                minOf(body.length, at + CONTEXT_CHARS)
            )}"
        )
    }
    return hits.groupBy { it.groupValues[1] }.entries.joinToString("; ") { (key, found) ->
        "$key x${found.size}: ${found.first().groupValues[2].replace('\n', ' ')}"
    }
}

private fun accountStart(videoId: String, accountStartMs: Long?, lengthSeconds: Long?) {
    val startMs = accountStartMs?.takeIf { it > 0 }
    val lengthMs = lengthSeconds?.times(MS_PER_S)
    if (startMs == null || lengthMs == null) {
        Diag.log("yt-sync", "$videoId: no account resume point to keep [start=$accountStartMs length=$lengthSeconds]")
        return
    }
    Diag.log("yt-sync", "$videoId: the account resumes it at ${startMs}ms of ${lengthMs}ms (from its player answer)")
    SeenProgress.saw(mapOf(videoId to AccountProgress(startMs, lengthMs)))
}

private const val MS_PER_S = 1_000L
private const val CONTEXT_CHARS = 160
