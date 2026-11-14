package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily

private const val HLS_MIME_TYPE = "application/vnd.apple.mpegurl"

public enum class CastFormat(
    public val file: String,
    public val mime: String,
    private val families: Set<VideoCodecFamily>?,
    private val containers: Set<Container>?,
) {
    DASH("manifest.mpd", Http.MPD, families = null, containers = null),
    HLS(
        "hls.m3u8",
        HLS_MIME_TYPE,
        families = setOf(VideoCodecFamily.AVC, VideoCodecFamily.HEVC),
        containers = setOf(Container.MP4),
    ),
    ;

    internal fun screenFor(screen: CastScreen?): CastScreen? = families?.let { carried ->
        (screen ?: CastScreen.of(null)).let { it.copy(codecs = it.codecs intersect carried) }
    } ?: screen

    internal fun carries(container: Container): Boolean = containers?.contains(container) ?: true

    public companion object {
        public const val HLS_MIME: String = HLS_MIME_TYPE
        internal const val HLS_VIDEO: String = "hls-video.m3u8"
        internal const val HLS_SOUND: String = "hls-audio.m3u8"

        internal fun isManifest(what: String): Boolean =
            entries.any { it.file == what } || what == HLS_VIDEO || what == HLS_SOUND
    }
}
