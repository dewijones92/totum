package com.dewijones92.totum.data.sponsorblock

import com.dewijones92.totum.domain.SkipSegment

public sealed interface SegmentLookup {
    public data class Answered(val segments: List<SkipSegment>) : SegmentLookup

    public data class Unavailable(val reason: String) : SegmentLookup
}

public fun interface FreshSkipSegments {
    public suspend fun lookup(videoId: String): SegmentLookup
}
