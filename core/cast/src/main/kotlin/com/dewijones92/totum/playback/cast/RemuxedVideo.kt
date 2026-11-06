package com.dewijones92.totum.playback.cast

public data class VideoSegment(val startMs: Long, val durationMs: Long)

public interface RemuxedVideo {
    public val codecs: String
    public val width: Int
    public val height: Int
    public val durationMs: Long
    public val timeline: List<VideoSegment>
    public val describe: String

    public fun init(): ByteArray

    public fun segment(n: Int, read: RangeReader): ByteArray
}
