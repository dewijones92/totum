package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant

public fun bannerAlert(previous: BannerLine?, current: BannerLine, at: Instant): String? = when {
    previous == null -> null
    current is BannerLine.OnBreak && previous !is BannerLine.OnBreak -> "break started"
    current is BannerLine.Sitting && previous is BannerLine.OnBreak -> "break over"
    current is BannerLine.Sitting && current.within(SUMMONS_WARNING, at) &&
        !(previous is BannerLine.Sitting && previous.within(SUMMONS_WARNING, at)) -> "summons in a minute"
    else -> null
}

private fun BannerLine.Sitting.within(warning: Duration, at: Instant): Boolean =
    summonsAt?.let { Duration.between(at, it) <= warning } == true

internal val SUMMONS_WARNING: Duration = Duration.ofMinutes(1)
