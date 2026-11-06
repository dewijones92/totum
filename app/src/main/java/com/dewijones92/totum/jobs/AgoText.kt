package com.dewijones92.totum.jobs

import android.content.Context
import com.dewijones92.totum.R

fun Context.agoText(ago: Ago): String = when (ago) {
    Ago.JustNow -> getString(R.string.ago_just_now)
    is Ago.Minutes -> resources.getQuantityString(R.plurals.ago_minutes, ago.count, ago.count)
    is Ago.Hours -> resources.getQuantityString(R.plurals.ago_hours, ago.count, ago.count)
    is Ago.Days -> resources.getQuantityString(R.plurals.ago_days, ago.count, ago.count)
}

fun Context.agoText(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String = agoText(agoOf(thenMs, nowMs))
