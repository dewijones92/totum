package com.dewijones92.totum.ui.kidtv

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.kidlock.KidTvFailure
import com.dewijones92.totum.kidlock.KidTvStatus

@Composable
internal fun kidTvStatusText(status: KidTvStatus, pinTitle: String?, tv: String?): String = when (status) {
    KidTvStatus.NotSetUp -> stringResource(R.string.kidtv_tile_set_up)
    KidTvStatus.Ready -> stringResource(R.string.kidtv_tile_ready, pinTitle.orEmpty(), tv.orEmpty())
    is KidTvStatus.Starting -> stringResource(R.string.kidtv_tile_starting, status.tv)
    is KidTvStatus.On -> stringResource(R.string.kidtv_tile_on, status.title, status.tv)
    is KidTvStatus.Failed -> kidTvFailureText(status.failure)
}

@Composable
private fun kidTvFailureText(failure: KidTvFailure): String = when (failure) {
    is KidTvFailure.PlaylistGone -> stringResource(R.string.kidtv_failed_gone)
    is KidTvFailure.TvNotFound ->
        if (failure.seen.isEmpty()) {
            stringResource(R.string.kidtv_failed_none_seen, failure.tv)
        } else {
            stringResource(R.string.kidtv_failed_not_found, failure.tv, failure.seen.joinToString())
        }
    KidTvFailure.NeedsLocalNetwork -> stringResource(R.string.kidtv_failed_network)
    is KidTvFailure.CastFailed -> stringResource(R.string.kidtv_failed_cast, failure.tv, failure.why)
    is KidTvFailure.PlayFailed -> stringResource(R.string.kidtv_failed_play, failure.title, failure.why)
}

@Composable
internal fun KidTvToasts(status: KidTvStatus) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(status) }
    val text = when (status) {
        is KidTvStatus.On -> stringResource(R.string.kidtv_toast_on, status.title, status.tv)
        is KidTvStatus.Failed -> kidTvFailureText(status.failure)
        else -> null
    }
    LaunchedEffect(status) {
        if (last is KidTvStatus.Starting && text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        last = status
    }
}
