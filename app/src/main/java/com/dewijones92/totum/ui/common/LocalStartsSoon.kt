package com.dewijones92.totum.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.video.StartsSoon
import com.dewijones92.totum.video.videoIdOf
import java.time.Instant

internal val LocalStartsSoon =
    staticCompositionLocalOf<State<Map<String, StartsSoon>>> { mutableStateOf(emptyMap()) }

@Composable
internal fun startsSoonOf(id: MediaItemId): StartsSoon? {
    val held = LocalStartsSoon.current
    val videoId = remember(id) { videoIdOf(id) } ?: return null
    return remember(held, videoId) { derivedStateOf { held.value[videoId] } }.value
}

@Composable
internal fun startsSoonLabel(soon: StartsSoon): String {
    val at = soon.startsAt ?: return stringResource(R.string.tag_starts_soon)
    return stringResource(R.string.tag_starts_at, LocalContext.current.startsAtText(at))
}

internal fun Context.startsAtText(at: Instant): String = DateUtils.formatDateTime(
    this,
    at.toEpochMilli(),
    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or
        if (DateUtils.isToday(at.toEpochMilli())) 0 else DateUtils.FORMAT_SHOW_DATE,
)
