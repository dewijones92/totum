package com.dewijones92.totum.video

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.audioLanguagePreference
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.innertube.player.audioTag

/**
 * A second opinion on what a video can stream, asked of YouTube directly.
 *
 * yt-dlp has deprecated extraction without a JavaScript runtime, and Chaquopy has no way to
 * give it one — so on Android it silently loses formats. Measured 2026-07-30 on a
 * made-for-kids video: yt-dlp on this device offers ONE format at 360p, while the same
 * video asked of `/player` as the ANDROID client offers 32 with working URLs up to 1080p.
 * With `--js-runtimes node` on a laptop yt-dlp gets the full ladder too, which is what
 * proved the runtime — not YouTube, and not SABR — was the thing missing.
 *
 * A FALLBACK, deliberately, and it was briefly the primary path. On the emulator it answered in
 * ~0.2s against extraction's 23.4s, which looked like a free win; on Dewi's actual phone the same
 * change measured 15-37 SECONDS per resolve and broke playback outright (report 0.1.312, 7 errors
 * and 17 stalls). The emulator's URLs carried no `n` and the phone's all do, so every resolve
 * there paid for a QuickJS solve. Reverted — see `VideoResolver.extractAndCache`.
 *
 * This comment used to say the URLs need no deciphering. That was true when written and is not
 * now — 140 of 140 formats on one video carry an obfuscated `n` — so a solver is supplied and
 * the response is only offered once its URLs are actually fetchable.
 */
fun interface PlayerStreams {
    /**
     * Null when YouTube refuses or the call fails; the caller falls back to yt-dlp.
     *
     * The whole response, not just its streams: [PlayerResult.Success.details] and its
     * subtitles are what let this resolve a video on its own rather than only supplement an
     * extraction that has already been paid for.
     */
    suspend fun playerFor(videoId: String): PlayerResult.Success?
}

/** [PlayerStreams] over our own InnerTube client. */
class InnerTubePlayerStreams(
    private val innerTube: InnerTubeClient,
    /**
     * The signed-in account, asked only when the anonymous call is refused.
     *
     * Age-restricted videos are the case: report 0.1.289 had three failing with "Sign in to
     * confirm your age… rated 15". yt-dlp has no credentials, but this app holds a YouTube
     * account already, and YouTube serves a rated video to a signed-in adult.
     *
     * Null disables the fallback, which is what tests and a signed-out app want.
     */
    private val account: AccountPlayer? = null,
    /**
     * Makes the anonymous response's URLs fetchable, by solving their `n` parameter.
     *
     * Required, not decorative: every URL this client returns now carries an obfuscated `n` and
     * 403s until it is transformed — measured 2026-08-02, 140 of 140 formats on one video and 36
     * of 36 on another. The comment above once claimed these URLs needed no deciphering, which
     * was true when it was written and silently stopped being true.
     *
     * Only the ANONYMOUS response passes through here. The account path solves its own before
     * returning, and solving twice would replace an already-correct value with a transform of
     * itself — which fails in exactly the way an unsolved one does, and would be far harder to
     * spot. Null leaves URLs untouched, which is what tests and previews want.
     */
    private val solveN: (suspend (StreamingData) -> StreamingData)? = null,
    /**
     * Whether to ask the ACCOUNT first rather than only as a rescue.
     *
     * The account path is a signed-in **TV** client, which is exactly what SmartTube is — and SmartTube
     * plays these videos in full from the same broadband on which this app cannot. Dewi, 2026-08-18:
     * *"they work great in smarttube"*. So the configuration that works is not a mystery to be
     * discovered; it is one we already implement and never reach.
     *
     * Never reached because the rescue only fired when the anonymous call was **refused**, and it is
     * not: it succeeds, hands back formats, and YouTube's SABR-only experiment then strips the video
     * URLs from them. A success that is useless does not look like a failure to a gate written for
     * failures — the fourth instance of that shape found today, and the one that matters most.
     *
     * Defaulted false so nothing changes for a signed-out app, previews, or existing tests.
     */
    private val preferAccount: () -> Boolean = { false },
) : PlayerStreams {

    /** What the signed-in retry needs: a token, and the timestamp streams are signed against. */
    fun interface AccountPlayer {
        suspend fun playerFor(videoId: String): PlayerResult?
    }

    override suspend fun playerFor(videoId: String): PlayerResult.Success? {
        signedInFirst(videoId)?.let { return it }
        val anonymous = anonymousPlayer(videoId)
        (anonymous as? PlayerResult.Success)?.let { return it }
        // Only now, because the signed-in call costs a token refresh and a second round trip, and
        // the overwhelming majority of videos never need it.
        val signedIn = account?.playerFor(videoId) as? PlayerResult.Success ?: return null
        Diag.log("resolve", "$videoId needed the signed-in account — age-restricted, most likely")
        return signedIn.describedByTheRefusal(videoId, anonymous)
    }

    /**
     * The signed-in response, borrowing the anonymous refusal's metadata when it has none of its own.
     *
     * The two hold different halves of one video and neither is enough alone: the signed-in TV client
     * supplies streams and NO readable metadata, while the refusal supplies title, author and length and
     * no streams. Joining them is what makes an age-restricted video showable as well as playable.
     */
    private fun PlayerResult.Success.describedByTheRefusal(
        videoId: String,
        anonymous: PlayerResult?,
    ): PlayerResult.Success {
        val describedBy = (anonymous as? PlayerResult.Unplayable)?.details
        if (details != null || describedBy == null) return this
        Diag.log("resolve", "$videoId described by the refused anonymous response: \"${describedBy.title}\"")
        return copy(details = describedBy)
    }

    /**
     * The signed-in TV client's answer, when we should be asking it first — null otherwise.
     *
     * Falls through to anonymous rather than failing if it gives nothing, because a token can expire
     * between the check and the call.
     */
    private suspend fun signedInFirst(videoId: String): PlayerResult.Success? {
        if (!preferAccount() || account == null) return null
        // The REASON, not just "gave nothing". This is the path that is currently broken -- TVHTML5
        // answers `UNPLAYABLE: The page needs to be reloaded.` for every request we can construct
        // (docs/todos/tv-client-player-is-refused.md) -- and a line that cannot distinguish that from
        // an expired token or a dead network makes the fix undebuggable from a report.
        val outcome = account.playerFor(videoId)
        val signedIn = outcome as? PlayerResult.Success
        Diag.log(
            "resolve",
            if (signedIn != null) {
                "$videoId resolved as the signed-in TV client"
            } else {
                "$videoId: the signed-in TV client gave nothing ($outcome); trying anonymously"
            },
        )
        return signedIn
    }

    /**
     * The same response with fetchable URLs, or null when none survived solving.
     *
     * Null rather than a response full of URLs that will 403: the caller then falls back to
     * extraction, which is slower but works. Handing back streams that cannot be fetched would
     * turn a slow start into a broken video.
     */
    private suspend fun PlayerResult.Success.playable(): PlayerResult.Success? {
        val solve = solveN ?: return this
        val solved = copy(streaming = solve(streaming))
        // A SABR endpoint counts as fetchable. Without this the SABR-only sessions -- the ones with no
        // direct URLs at all, which is the whole reason the SABR path was built -- were discarded here
        // and `overSabr` never got to ask. See StreamingData.playableSomehow.
        if (!solved.streaming.playableSomehow) {
            Diag.log("resolve", "the fast player response had nothing fetchable after solving n, and no SABR endpoint")
            return null
        }
        if (solved.streaming.directlyPlayable.isEmpty()) {
            Diag.log("resolve", "nothing directly fetchable after solving n, but a SABR session is offered")
        }
        return solved
    }

    /** The parsed anonymous response, refusals included — see [playerFor] for why they matter. */
    private suspend fun anonymousPlayer(videoId: String): PlayerResult? {
        val response = runCatching { innerTube.player(videoId) }.getOrElse { failure ->
            Diag.warn("resolve", "second opinion for $videoId could not be fetched", failure)
            return null
        }
        val body = (response as? InnerTubeResponse.Success)?.body ?: run {
            Diag.log("resolve", "second opinion for $videoId: $response")
            return null
        }
        return when (val parsed = PlayerResponseParser.parse(body)) {
            is PlayerResult.Success -> parsed.playable()
            is PlayerResult.Unplayable -> {
                Diag.log("resolve", "second opinion for $videoId refused: ${parsed.reason}")
                parsed
            }
            is PlayerResult.Failure -> {
                Diag.warn("resolve", "second opinion for $videoId unreadable: ${parsed.detail}")
                null
            }
        }
    }
}

/**
 * The same quality ladder as [videoQualities], built from a `/player` response.
 *
 * Kept beside the yt-dlp mapping rather than merged with it: the two inputs describe formats
 * differently (a `MediaFormat` knows codecs and whether it has audio; a `PlayableFormat`
 * carries only a mime type), so a shared function would be a shared function with two
 * disjoint halves. What IS shared is [VideoQuality], which is the part that matters.
 */
internal fun StreamingData.videoQualities(wanted: List<String> = emptyList()): List<VideoQuality> {
    val audio = bestAudioFormat(wanted)
    val byAudio = audioLanguagePreference(wanted)
    // The bar every height has to clear: the best sound anywhere on this video, muxed streams
    // included. Without it a muxed rung was preferred on being muxed alone, so asking for German
    // where German exists only as an audio-only track served the English muxed — report 0.1.373's
    // bug reached by the second-opinion route. See ASecondOpinionRungKeepsYourTrackTest.
    val bestSound = directlyPlayable.filter { it.carriesSound }
        .map { it.audioTag }
        .maxWithOrNull(byAudio) ?: AudioTrackTag.Unknown

    return directlyPlayable
        .filter { it.height != null && it.mimeType?.startsWith("video/") == true }
        .groupBy { it.height!! }
        .mapNotNull { (height, atHeight) ->
            // A muxed stream needs no merge, so prefer it — but only while its sound is what you
            // asked for; otherwise pair video with the best audio, exactly as the yt-dlp path does.
            val muxed = atHeight.filter { it.carriesSound }.bestSounding(wanted)
            val videoOnly = atHeight.filterNot { it.carriesSound }.maxByOrNull { it.bitrate ?: 0 }
            when {
                muxed != null && byAudio.compare(muxed.audioTag, bestSound) >= 0 ->
                    muxed.url?.let { quality(height, it, audioUrl = null, audio = muxed.audioTag) }
                // Nothing at this height speaks the language you asked for and there is no
                // video-only stream to pair with the audio that does, so the height is dropped:
                // the ladder is the ladder for the track you are listening to. Offering it anyway
                // sends the auto-pick — which takes the tallest — back to the dub.
                videoOnly != null && audio != null && byAudio.compare(audio.audioTag, bestSound) >= 0 ->
                    videoOnly.url?.let { quality(height, it, audio.url, audio.audioTag) }
                else -> null
            }
        }
        .sortedByDescending { it.height }
}

/** Whether this format carries audio at all: an audio-only track, or a muxed stream. */
private val PlayableFormat.carriesSound: Boolean
    get() = mimeType?.startsWith("audio/") == true || mimeType?.contains("mp4a") == true

private fun quality(height: Int, url: HttpUrl, audioUrl: HttpUrl?, audio: AudioTrackTag) =
    VideoQuality("$height", "${height}p", height, url, audioUrl, audio = audio)

/** Best audio-only stream, for "Listen" mode and as the merge partner for a video-only one. */
internal fun StreamingData.bestAudioUrl(wanted: List<String> = emptyList()): HttpUrl? =
    bestAudioFormat(wanted)?.url

private fun StreamingData.bestAudioFormat(wanted: List<String>): PlayableFormat? =
    directlyPlayable.filter { it.mimeType?.startsWith("audio/") == true }.bestSounding(wanted)

/**
 * The best single stream that carries picture AND sound, or null when every format is split.
 *
 * The default the app plays, matching what the yt-dlp path picks: one stream is reliable and
 * data-friendly, and the quality menu offers the higher merged ladders on demand. Choosing the
 * tallest format here instead would quietly make every play a merged 2160p one.
 */
internal fun StreamingData.bestMuxedUrl(wanted: List<String> = emptyList()): HttpUrl? =
    directlyPlayable
        .filter { it.mimeType?.startsWith("video/") == true && it.mimeType?.contains("mp4a") == true }
        .bestSounding(wanted, tieBreak = compareBy { it.height ?: 0 })
        ?.url

/** Language first, then [tieBreak] — the same rule the yt-dlp path uses. */
private fun List<PlayableFormat>.bestSounding(
    wanted: List<String>,
    tieBreak: Comparator<PlayableFormat> = compareBy { it.bitrate ?: 0 },
): PlayableFormat? = filter { it.url != null }.maxWithOrNull(
    compareBy(audioLanguagePreference(wanted)) { format: PlayableFormat -> format.audioTag }.then(tieBreak),
)
