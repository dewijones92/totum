package com.dewijones92.totum.ui.treat

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
import com.dewijones92.totum.treat.TreatChoice
import com.dewijones92.totum.treat.TreatEnd
import com.dewijones92.totum.treat.TreatFailure
import com.dewijones92.totum.treat.TreatRules
import com.dewijones92.totum.treat.TreatStatus

@Composable
internal fun treatEndWords(end: TreatEnd): String = stringResource(
    when (end) {
        TreatEnd.SWITCH_OFF -> R.string.treat_then_switch_off
        TreatEnd.PAUSE -> R.string.treat_then_pause
        TreatEnd.STOP -> R.string.treat_then_stop
    },
)

@Composable
internal fun treatStatusText(status: TreatStatus, choice: TreatChoice, pinTitle: String?, nowMs: Long): String =
    when (status) {
        TreatStatus.NotSetUp -> stringResource(R.string.treat_tile_set_up)
        TreatStatus.Ready -> stringResource(
            R.string.treat_tile_ready,
            pinTitle.orEmpty(),
            choice.targetLabel ?: choice.targetId.orEmpty(),
            choice.minutes,
            treatEndWords(choice.end),
        )
        is TreatStatus.Starting -> stringResource(R.string.treat_tile_starting, status.title, status.tv)
        is TreatStatus.On -> stringResource(
            R.string.treat_tile_on,
            status.title,
            status.tv,
            TreatRules.minutesLeft(status.endsAtMs - nowMs),
        )
        is TreatStatus.Failed -> treatFailureText(status.failure)
    }

@Composable
internal fun treatFailureText(failure: TreatFailure): String = when (failure) {
    is TreatFailure.PlaylistGone -> stringResource(R.string.treat_failed_gone)
    is TreatFailure.NothingToPlay -> stringResource(R.string.treat_failed_nothing, failure.title, failure.why)
    is TreatFailure.TvNotFound ->
        if (failure.seen.isEmpty()) {
            stringResource(R.string.treat_failed_tv_none, failure.tv)
        } else {
            stringResource(R.string.treat_failed_tv, failure.tv, failure.seen.joinToString())
        }
    is TreatFailure.WontTake -> stringResource(R.string.treat_failed_wont, failure.tv, failure.why)
    is TreatFailure.TimerNotSet -> stringResource(R.string.treat_failed_timer, failure.tv, failure.why)
}

@Composable
internal fun TreatToasts(status: TreatStatus) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(status) }
    val on = status as? TreatStatus.On
    val text = when (status) {
        is TreatStatus.On -> stringResource(
            R.string.treat_toast_on,
            status.title,
            status.tv,
            TreatRules.minutesLeft(status.endsAtMs - System.currentTimeMillis()),
        )
        is TreatStatus.Failed -> treatFailureText(status.failure)
        else -> null
    }
    val fellBack = on?.takeIf { it.fellBack }?.let { stringResource(R.string.treat_toast_fell_back, it.tv) }
    LaunchedEffect(status) {
        if (last is TreatStatus.Starting && text != null) {
            Toast.makeText(context, listOfNotNull(text, fellBack).joinToString("\n"), Toast.LENGTH_LONG).show()
        }
        last = status
    }
}
