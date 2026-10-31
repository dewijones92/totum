package com.dewijones92.totum.ui.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.rememberField

@Composable
fun CastStrip(state: PlaybackState, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    if (!state.onCastDevice && state.castDeviceName == null) return
    val device = state.castDeviceName ?: stringResource(R.string.cast_strip_unnamed_device)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { testTag = CAST_STRIP_TAG },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cast_strip_casting, device),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                actions()
                ScreenButton()
                CastButton(modifier = Modifier.size(CAST_BUTTON_SIZE))
            }
            state.castStage?.let { stage -> CastStageLine(stage, state.castStagePercent, device) }
            (state.castFetchHeight ?: state.castVideoHeight)?.let { height ->
                val best = state.castVideoHeight
                Text(
                    if (best != null && best > height) {
                        stringResource(R.string.cast_strip_quality_climbing, height, device, best)
                    } else {
                        stringResource(R.string.cast_strip_quality, height, device)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                )
            }
            state.castProblem?.let { problem ->
                Text(
                    when (problem) {
                        CastProblem.NO_ADDRESS -> stringResource(R.string.cast_strip_no_address, device)
                        CastProblem.TV_NOT_FETCHING -> stringResource(R.string.cast_strip_not_fetching, device)
                        CastProblem.VPN_IN_THE_WAY -> stringResource(R.string.cast_strip_vpn, device)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                )
            }
            state.castVideoRefused?.let { what ->
                Text(
                    stringResource(R.string.cast_strip_sound_only, device, what),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CastStageLine(stage: CastStage, percent: Int?, device: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(end = 8.dp, bottom = 6.dp).semantics { testTag = CAST_STAGE_TAG },
    ) {
        if (percent == null) {
            CircularProgressIndicator(modifier = Modifier.size(STAGE_SPINNER), strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(
                progress = { percent / PERCENT },
                modifier = Modifier.size(STAGE_SPINNER),
                strokeWidth = 2.dp
            )
        }
        Text(
            when (stage) {
                CastStage.PREPARING -> stringResource(R.string.cast_strip_stage_preparing, device)
                CastStage.REPACKAGING -> stringResource(R.string.cast_strip_stage_repackaging, device, percent ?: 0)
                CastStage.TV_STARTING -> stringResource(R.string.cast_strip_stage_starting, device)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

const val CAST_STAGE_TAG: String = "cast-stage"
private val STAGE_SPINNER = 18.dp
private const val PERCENT = 100f

const val CAST_STRIP_TAG: String = "cast-strip"
private val CAST_BUTTON_SIZE = 40.dp

@Composable
fun rememberCastingTo(controller: PlaybackController): String? {
    val unnamed = stringResource(R.string.cast_strip_unnamed_device)
    val device by controller.castDevice.collectAsStateWithLifecycle()
    val remote by controller.rememberField { it?.onCastDevice == true }
    return device ?: unnamed.takeIf { remote }
}
