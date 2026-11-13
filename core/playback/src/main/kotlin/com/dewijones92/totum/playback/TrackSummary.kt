package com.dewijones92.totum.playback

internal object TrackSummary {
    fun of(mimes: List<String?>, seekable: Boolean?): String {
        val tracks = if (mimes.isEmpty()) "none" else mimes.joinToString { it ?: "unknown type" }
        val seek = when (seekable) {
            true -> "seekable"
            false -> "not seekable"
            null -> "seekability unknown"
        }
        return "${mimes.size} track(s): $tracks; $seek"
    }
}
