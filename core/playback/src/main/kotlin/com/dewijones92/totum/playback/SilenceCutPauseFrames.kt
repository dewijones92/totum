package com.dewijones92.totum.playback

internal object SilenceCutPauseFrames {

    const val LATE_TOLERANCE_US = 30_000L

    fun dropped(presentationUs: Long, positionUs: Long, owedUs: Long, isLastBuffer: Boolean): Boolean =
        owedUs > 0 && !isLastBuffer && presentationUs < positionUs + owedUs - LATE_TOLERANCE_US
}
