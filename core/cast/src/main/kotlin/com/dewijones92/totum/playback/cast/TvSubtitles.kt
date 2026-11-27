package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.SubtitleTrack

public object TvSubtitles {
    public const val VTT: String = "text/vtt"
    public const val SUBRIP: String = "application/x-subrip"

    public fun carries(mime: String?): Boolean = mime == VTT || mime == SUBRIP

    public fun from(tracks: List<SubtitleTrack>): List<CastSubtitleSource> = tracks
        .filter { carries(it.format.mimeType) }
        .map { CastSubtitleSource(it.url.value, it.languageCode, it.label) }
}
