package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage

public class CastTv(public val name: String, public val screen: CastScreen)

public interface CastTvPort {
    public fun current(): CastTv?
    public fun videos(refused: Map<String, String>, sentHeights: Map<String, Int>, fetchingHeights: Map<String, Int>)
    public fun problem(now: CastProblem?)

    public fun stage(mediaId: String, now: CastStage?, percent: Int? = null) {}
}
