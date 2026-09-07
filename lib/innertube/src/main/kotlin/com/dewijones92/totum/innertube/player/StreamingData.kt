package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleTrack

/**
 * What YouTube says it can stream for a video.
 *
 * The first stone of our own playback path. Today the app resolves streams through yt-dlp,
 * which extracts plain URLs — and YouTube is deliberately moving away from handing those
 * out. This reads the same player response yt-dlp does, but keeps the formats yt-dlp has to
 * discard: the ones with no [PlayableFormat.url], reachable only through
 * [serverAbrStreamingUrl] over YouTube's SABR/UMP protocol.
 *
 * Knowing about them is useful before we can fetch them. It is the difference between "this
 * video is 360p" and "this video is 1080p and we cannot reach it yet", which is what the app
 * currently gets wrong. See docs/todos/sabr-streaming.md.
 */
public data class StreamingData(
    val formats: List<PlayableFormat>,
    /**
     * Where SABR segments are requested from, when YouTube withholds direct URLs. Null when
     * every format is directly fetchable, which is still the common case.
     */
    val serverAbrStreamingUrl: HttpUrl? = null,
    /**
     * `videoPlaybackUstreamerConfig`, base64url-decoded — the one field a SABR request cannot
     * omit. Without it the server answers `RELOAD_PLAYER_RESPONSE: sabr.malformed_config`.
     */
    val ustreamerConfig: ByteArray? = null,
) {
    /** Formats we could play today — those with a direct URL. */
    public val directlyPlayable: List<PlayableFormat> get() = formats.filter { it.url != null }

    /**
     * Whether this response can be played AT ALL — by a direct URL, or over SABR.
     *
     * The distinction matters because YouTube runs its SABR-only experiment per session: when it does,
     * it strips every format's URL and keeps `serverAbrStreamingUrl` and the ustreamer config, so the
     * response looks empty to anything that only counts [directlyPlayable] while still carrying
     * everything the SABR path needs. Judging usefulness by direct URLs alone therefore discarded
     * precisely the sessions SABR exists to rescue — measured 2026-08-19, a stripped response still
     * served bytes for itags 140, 135 and 134.
     *
     * ONE definition, because two callers ask it (`InnerTubePlayerStreams.playable` and
     * `AppContainer.withPlayableStreams`) and a second copy would drift.
     */
    public val playableSomehow: Boolean
        get() = directlyPlayable.isNotEmpty() || (serverAbrStreamingUrl != null && ustreamerConfig != null)

    /** The best height YouTube offers, whether or not we can currently fetch it. */
    public val bestOfferedHeight: Int? get() = formats.mapNotNull { it.height }.maxOrNull()

    /** The best height we can actually fetch right now. */
    public val bestReachableHeight: Int? get() = directlyPlayable.mapNotNull { it.height }.maxOrNull()

    /**
     * True when YouTube is offering more than we can take — the SABR signature. Worth
     * naming: it is the one condition under which the app silently plays a worse video than
     * the one available.
     */
    public val degraded: Boolean
        get() {
            val offered = bestOfferedHeight ?: return false
            return offered > (bestReachableHeight ?: 0)
        }
}

/** One stream. [url] is null when YouTube will only serve it over SABR. */
public data class PlayableFormat(
    val itag: Int,
    val mimeType: String?,
    val height: Int?,
    val bitrate: Long?,
    val url: HttpUrl?,
    /**
     * Identifies this format to SABR, together with [xtags]. A real response carried 22
     * entries for one audio itag — one per dubbed language — so the itag alone names nothing.
     */
    val lastModified: Long? = null,
    /** YouTube's `xtags`, verbatim. Without it SABR answers `sabr.no_audio_selected`. */
    val xtags: String? = null,
    /**
     * Frames per second, when stated. Needed because SABR refuses 60fps formats outright — see
     * `SabrResolve.bestVideo`.
     */
    val fps: Int? = null,
    /**
     * The format's TOTAL length in bytes, as the player response states it.
     *
     * Not to be confused with a `MEDIA_HEADER`'s `contentLength`, which is one RUN's length —
     * reading that as the total said "432274B of 807B" and made the stream think it was
     * complete before it began.
     */
    val contentLength: Long? = null,
)

/**
 * What this format's sound is, from `xtags` and from the URL, which says the same thing.
 *
 * Both are read because neither is always present: a SABR-only format carries `xtags` and no
 * URL, and an HLS variant carries a URL that labels itself and often nothing else. Unknown for
 * a video-only format, which claims nothing about audio it does not have.
 */
public val PlayableFormat.audioTag: AudioTrackTag
    get() {
        if (mimeType?.startsWith("video/") == true && mimeType.contains("mp4a") != true) return AudioTrackTag.Unknown
        val tagged = AudioTrackTag.fromXtags(xtags)
        val inUrl = AudioTrackTag.inUrl(url?.value)
        return AudioTrackTag(
            languageCode = tagged.languageCode ?: inUrl.languageCode,
            original = tagged.original || inUrl.original,
            dubbed = tagged.dubbed || inUrl.dubbed,
        )
    }

public sealed interface PlayerResult {
    /**
     * [details] and [subtitles] are what let this response resolve a video on its own rather
     * than only supplement yt-dlp's answer. They arrive in the same request as the streams.
     */
    public data class Success(
        val streaming: StreamingData,
        val details: PlayerDetails? = null,
        val subtitles: List<SubtitleTrack> = emptyList(),
        /** The client this was asked as — see [PlayerClient]; the default is the anonymous streams client. */
        val client: PlayerClient = PlayerClient.ANDROID,
    ) : PlayerResult

    /**
     * YouTube refused: age-gated, members-only, region-blocked, bot-checked.
     *
     * [details] survives the refusal on purpose. Only the STREAMS are gated — a refused response
     * still names the video — and that is the only place the app can learn a title for one it
     * then plays with a signed-in TV response, which supplies streams and no metadata at all.
     */
    public data class Unplayable(
        val reason: String,
        val details: PlayerDetails? = null,
    ) : PlayerResult
    public data class Failure(val detail: String) : PlayerResult
}
