package com.dewijones92.totum.ui.queue

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.TargetConnection
import com.dewijones92.totum.data.targets.TargetKind

internal fun TargetKind.icon(): ImageVector = when (this) {
    TargetKind.PHONE -> Icons.Outlined.PhoneAndroid
    TargetKind.SPEAKER -> Icons.Outlined.Speaker
    TargetKind.TV -> Icons.Outlined.Tv
    TargetKind.CHROMECAST -> Icons.Outlined.Cast
    TargetKind.BROWSER -> Icons.Outlined.Language
}

@Composable
internal fun TargetConnection.words(): String = stringResource(
    when (this) {
        TargetConnection.LINKED -> R.string.target_link_linked
        TargetConnection.READY -> R.string.target_link_ready
        TargetConnection.WAKING -> R.string.target_link_waking
        TargetConnection.ASLEEP -> R.string.target_link_asleep
        TargetConnection.RECONNECTING -> R.string.target_link_reconnecting
        TargetConnection.PROBLEM -> R.string.target_link_problem
    },
)

internal fun TargetConnection.dotColour(): Color = when (this) {
    TargetConnection.LINKED -> LINKED_GREEN
    TargetConnection.READY -> READY_GREY
    TargetConnection.WAKING, TargetConnection.RECONNECTING -> WORKING_AMBER
    TargetConnection.ASLEEP -> ASLEEP_GREY
    TargetConnection.PROBLEM -> PROBLEM_RED
}

internal fun Color.readableOn(): Color = if (luminance() > BLACK_READS_BETTER_ABOVE) Color.Black else Color.White

private val LINKED_GREEN = Color(0xFF2E9E5B)
private val READY_GREY = Color(0xFF9AA0A6)
private val WORKING_AMBER = Color(0xFFE0A100)
private val ASLEEP_GREY = Color(0xFF5F6368)
private val PROBLEM_RED = Color(0xFFD93025)
private const val BLACK_READS_BETTER_ABOVE = 0.179f
