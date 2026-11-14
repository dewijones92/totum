package com.dewijones92.totum.playback.cast

private const val HLS_MIME_TYPE = "application/vnd.apple.mpegurl"

public enum class CastFormat(public val file: String, public val mime: String, private val soundOnly: Boolean) {
    DASH("manifest.mpd", Http.MPD, soundOnly = false),
    HLS("hls.m3u8", HLS_MIME_TYPE, soundOnly = true),
    ;

    internal fun shaped(item: CastItem): CastItem =
        if (soundOnly) item.copy(listening = true, ladder = emptyList()) else item

    public companion object {
        public const val HLS_MIME: String = HLS_MIME_TYPE

        internal fun isManifest(what: String): Boolean = entries.any { it.file == what }
    }
}
