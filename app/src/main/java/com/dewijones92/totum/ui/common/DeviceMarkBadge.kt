package com.dewijones92.totum.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.targets.DeviceMark

internal val LocalDeviceMarks =
    staticCompositionLocalOf<State<Map<String, DeviceMark>>> { mutableStateOf(emptyMap()) }

@Composable
internal fun deviceMarkOf(id: MediaItemId): DeviceMark? {
    val marks = LocalDeviceMarks.current
    return remember(marks, id) { derivedStateOf { marks.value[id.value] } }.value
}

@Composable
internal fun DeviceMarkBadge(id: MediaItemId, modifier: Modifier = Modifier) {
    val mark = deviceMarkOf(id) ?: return
    val colour = Color(mark.colour.toInt())
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colour.copy(alpha = MARK_FILL),
        border = BorderStroke(1.dp, colour),
        modifier = modifier.testTag(DEVICE_MARK_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = mark.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

const val DEVICE_MARK_TAG = "device-mark"
private const val MARK_FILL = 0.18f
