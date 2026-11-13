package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.dewijones92.totum.R

@Composable
internal fun retryWait(seconds: Long): String = if (seconds < SECONDS_A_MINUTE) {
    pluralStringResource(R.plurals.downloads_wait_seconds, seconds.toInt(), seconds)
} else {
    val minutes = (seconds + SECONDS_A_MINUTE - 1) / SECONDS_A_MINUTE
    pluralStringResource(R.plurals.downloads_wait_minutes, minutes.toInt(), minutes)
}

private const val SECONDS_A_MINUTE = 60L
