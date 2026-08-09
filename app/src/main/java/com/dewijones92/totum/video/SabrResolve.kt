package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.common.audioLanguagePreference
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.innertube.player.audioTag
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions

/**
 * Turns a `/player` response into a registered SABR session and the URLs that play it.
 *
 * This is the shipping edge of the SABR work. A `/player` call answers in about 150ms with a
 * full ladder where a yt-dlp extraction costs 2-4 seconds on a phone, and SABR is the only way
 * to fetch the bytes those URLs describe — measured 2026-07-31, a plain ranged GET of an
 * ANDROID-client stream URL serves its first megabyte and then 403s forever.
 *
 * Returns null rather than guessing whenever the response is not completely usable, and the
 * caller extracts as it always has. Every reason is logged, because "SABR did not happen" with
 * no explanation would be the hardest kind of bug to chase.
 */
internal object SabrResolve {

    /** A playable pair of `sabr://` URLs, plus the details that describe the video. */
    data class Resolved(
        val details: PlayerDetails,
        val videoUrl: HttpUrl?,
        val audioUrl: HttpUrl,
    )

    fun prepare(
        videoId: String,
        streaming: StreamingData,
        details: PlayerDetails?,
        /** Audio languages to prefer — see `AudioTrackTag`. */
        wanted: List<String> = emptyList(),
    ): Resolved? {
        val endpoint = streaming.serverAbrStreamingUrl?.value ?: return refuse(videoId, "no SABR endpoint")
        val config = streaming.ustreamerConfig ?: return refuse(videoId, "no ustreamer config")
        val known = details ?: return refuse(videoId, "no videoDetails")

        val audio = streaming.formats.bestAudio(wanted) ?: return refuse(videoId, "no identifiable audio format")
        // Video works now. MEDIA parts are routed by the header id they CARRY rather than by
        // the last header seen — runs interleave, so the old attribution spliced one format's
        // bytes into another's and ExoPlayer reported "Invalid NAL length".
        val video = streaming.formats.bestVideo()

        SabrSessions.register(
            videoId,
            SabrSession(
                endpoint,
                config,
                audio.toSabrFormat(),
                video?.toSabrFormat(),
                known.lengthSeconds?.times(MILLIS_PER_SECOND),
            ),
        )
        val audioUrl = SabrSessions.uriFor(videoId, audio.itag)?.let(HttpUrl::parse)
            ?: return refuse(videoId, "could not build a marked endpoint URL")
        reportQuality(videoId, streaming.formats, audio, video)
        return Resolved(
            details = known,
            videoUrl = video?.let { SabrSessions.uriFor(videoId, it.itag)?.let(HttpUrl::parse) },
            audioUrl = audioUrl,
        )
    }

    /**
     * The best audio SABR will actually serve.
     *
     * `lastModified` is required because it identifies the format, and itag 139 is excluded by
     * name: probing every audio format on 2026-07-31, 139 answered `sabr.no_audio_selected`
     * while 140, 249, 251, 599 and 600 all served. A listed format is not necessarily an
     * obtainable one, and choosing purely by bitrate would pick a refused one soon enough.
     *
     * Language before bitrate, like every other picker. A real player response carried **22
     * entries for one audio itag**, one per dubbed language, so bitrate alone names a language
     * at random — which is how report 0.1.373 happened on the extraction path.
     */
    private fun List<PlayableFormat>.bestAudio(wanted: List<String>): PlayableFormat? =
        filter { it.mimeType?.startsWith("audio/") == true }
            .filter { it.lastModified != null && it.itag !in REFUSED_ITAGS }
            .maxWithOrNull(
                compareBy(audioLanguagePreference(wanted)) { format: PlayableFormat -> format.audioTag }
                    .thenBy { it.bitrate ?: 0 },
            )

    /**
     * The best video SABR will actually serve, which is a narrower set than the one listed.
     *
     * Probed every video format of a real video on 2026-07-31, and the pattern is total:
     *
     * | Container | Result |
     * |---|---|
     * | `video/webm` (VP9) — itags 313, 271, 248, 247, 244, 243, 242, 278, 598 | **every one refused** |
     * | `video/mp4` (H.264 and AV1) — 137, 400, 399, 398, 397, 396, 136, 135, 134, 133, 160, 394 | served |
     *
     * VP9 answers `sabr.no_video_selected` without exception, so mp4 is required. Note this is
     * video-only: the audio track we use is `audio/webm` opus and serves perfectly.
     *
     * Capped at 1080p because the two mp4 refusals were both outside the ordinary range (2160p
     * AV1, and a 240p AV1 oddity). 1080p is also plenty on a phone, so the cap costs nothing
     * and avoids a class of failure rather than guessing at its edges.
     */
    private fun List<PlayableFormat>.bestVideo(): PlayableFormat? =
        filter { it.mimeType?.contains("mp4") == true && it.height != null }
            // Video-ONLY. A muxed format (itag 18 and friends, carrying `mp4a` in a video mime)
            // is a legacy progressive stream, not one of SABR's adaptive tracks: asking for it
            // got bytes ExoPlayer could not recognise as any container at all
            // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED). The audio arrives as its own track.
            .filterNot { it.mimeType?.contains("mp4a") == true }
            // 30fps ONLY. On a 4K/60fps video, EVERY 60fps format is refused — 315, 337, 701,
            // 308, 336, 700, 299, 303, 335, 699, 298, 302, 334, 698 and the rest — while
            // 135/134/133/160 at 30fps serve. Declaring MediaCapabilities with a 2160p60 video
            // capability did NOT unlock them, for any codec id 0-8, so this is a real server-side
            // restriction and not a missing field on our side.
            //
            // The cost is honest: a 60fps upload plays at whatever 30fps rung it offers, which on
            // one 4K video was 480p. That is worse quality but it PLAYS, where before the whole
            // request came back empty.
            .filterNot { (it.fps ?: 0) > MAX_SABR_FPS }
            .filter { it.lastModified != null && (it.height ?: 0) <= MAX_SABR_HEIGHT }
            .maxByOrNull { it.height ?: 0 }

    private fun PlayableFormat.toSabrFormat() = SabrFormat(itag, lastModified!!, xtags, contentLength)

    /**
     * The quality the user is ACTUALLY getting, against what YouTube offered.
     *
     * The 60fps refusal can quietly drop a 4K/60 upload to 480p, and a report saying only "SABR
     * was used" would make that invisible. This is the line that answers "why did it look worse
     * than usual".
     */
    private fun reportQuality(
        videoId: String,
        formats: List<PlayableFormat>,
        audio: PlayableFormat,
        video: PlayableFormat?,
    ) {
        val offered = formats.mapNotNull { it.height }.maxOrNull() ?: 0
        val chosen = video?.height ?: 0
        val capped = if (chosen in 1 until offered) " — CAPPED, ${whyCapped(formats, offered)}" else ""
        Diag.log(
            "sabr",
            "prepared $videoId — audio itag ${audio.itag}" +
                (audio.bitrate?.let { " @${it / BITS_PER_KILOBIT}kbps" } ?: "") +
                ", video ${video?.let { "itag ${it.itag} ${it.height}p${it.fps ?: ""}" } ?: "none"}" +
                ", YouTube offered up to ${offered}p$capped",
        )
        Vitals.set("sabr.quality", "${chosen}p of ${offered}p offered")
    }

    /**
     * Why the chosen height is below what YouTube offered — named, not left to be inferred.
     *
     * There are only a few reasons and each is a rule measured on 2026-07-31, so a report can
     * say which one bit rather than leaving "it played at 480p" as the whole story.
     */
    private fun whyCapped(formats: List<PlayableFormat>, offered: Int): String {
        val better = formats.filter { (it.height ?: 0) > MAX_SABR_HEIGHT || (it.fps ?: 0) > MAX_SABR_FPS }
        val sixtyFps = better.count { (it.fps ?: 0) > MAX_SABR_FPS }
        val webm = formats.count { it.mimeType?.contains("webm") == true && it.height != null }
        return "SABR refuses ${sixtyFps}x 60fps and ${webm}x VP9/webm formats; " +
            "our own cap is ${MAX_SABR_HEIGHT}p (offered ${offered}p)"
    }

    private fun refuse(videoId: String, why: String): Resolved? {
        Diag.log("sabr", "not using SABR for $videoId: $why — extracting instead")
        return null
    }

    /** Formats YouTube lists but will not serve over SABR. */
    private val REFUSED_ITAGS = setOf(139)

    /** Above this, mp4 formats started being refused too; and it is plenty on a phone. */
    private const val MAX_SABR_HEIGHT = 1080

    /** SABR refuses every format above this, whatever its codec or resolution. */
    private const val MAX_SABR_FPS = 30

    private const val BITS_PER_KILOBIT = 1000
    private const val MILLIS_PER_SECOND = 1000L
}
