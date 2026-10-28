package com.dewijones92.totum.playback.cast

internal data class VideoSegment(val startMs: Long, val durationMs: Long)

internal interface RemuxedVideo {
    val codecs: String
    val width: Int
    val height: Int
    val durationMs: Long
    val timeline: List<VideoSegment>
    val describe: String

    fun init(): ByteArray

    fun segment(n: Int, read: RangeReader): ByteArray
}
