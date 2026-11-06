package com.dewijones92.totum.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.ActiveMode
import com.dewijones92.totum.ui.player.speedLabel
import com.dewijones92.totum.workapps.WorkAppsQuietActivity
import kotlinx.coroutines.flow.StateFlow

internal val LocalActiveModes = staticCompositionLocalOf<StateFlow<List<ActiveMode>>?> { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveModesRow(from: String, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val modes by (LocalActiveModes.current ?: return).collectAsStateWithLifecycle()
    if (modes.isEmpty()) return
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().testTag(ACTIVE_MODES_TAG),
    ) {
        modes.forEach { mode ->
            Surface(
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .clickable {
                        Diag.log("modes", "dewidebug mode chip ${mode.diagName} tapped in the $from")
                        if (mode == ActiveMode.QuietHours) {
                            context.startActivity(WorkAppsQuietActivity.intent(context, "$from mode chip"))
                        } else {
                            onOpenPlayer()
                        }
                    }
                    .testTag("$ACTIVE_MODE_TAG:${mode.diagName}"),
            ) {
                Text(
                    text = mode.label(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveMode.label(): String = when (this) {
    ActiveMode.RepeatOne -> stringResource(R.string.mode_repeat_one)
    ActiveMode.RepeatGroup -> stringResource(R.string.mode_repeat_group)
    ActiveMode.RepeatQueue -> stringResource(R.string.mode_repeat_queue)
    ActiveMode.Shuffle -> stringResource(R.string.mode_shuffle)
    ActiveMode.Explore -> stringResource(R.string.mode_explore)
    is ActiveMode.Speed -> stringResource(R.string.mode_speed, speedLabel(rate))
    ActiveMode.KidLock -> stringResource(R.string.mode_kid_lock)
    ActiveMode.QuietHours -> stringResource(R.string.mode_quiet_hours)
    ActiveMode.SleepTimer -> stringResource(R.string.mode_sleep_timer)
    ActiveMode.DataLimit -> stringResource(R.string.mode_data_limit)
}

const val ACTIVE_MODES_TAG = "active-modes"
const val ACTIVE_MODE_TAG = "active-mode"
