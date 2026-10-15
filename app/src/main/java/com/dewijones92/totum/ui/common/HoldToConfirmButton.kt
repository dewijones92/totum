package com.dewijones92.totum.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun HoldToConfirmButton(
    label: String,
    hint: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Int = HOLD_MS,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showHint by remember { mutableStateOf(false) }
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = FILL_ALPHA)
    val shape = RoundedCornerShape(8.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .testTag(HOLD_TO_CONFIRM_TAG)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .drawBehind { drawRect(fill, size = Size(size.width * progress.value, size.height)) }
                .semantics {
                    onLongClick(label = label) {
                        onConfirmed()
                        true
                    }
                }
                .pointerInput(holdMs) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var done = false
                        val hold: Job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                            done = true
                            Diag.log("confirm", "\"$label\" held for ${holdMs}ms; confirmed")
                            onConfirmed()
                        }
                        waitForUpOrCancellation()
                        if (!done) {
                            hold.cancel()
                            scope.launch { progress.snapTo(0f) }
                            showHint = true
                            Diag.log("confirm", "\"$label\" released before ${holdMs}ms; not confirmed")
                        }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        if (showHint) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

const val HOLD_TO_CONFIRM_TAG = "hold-to-confirm"
private const val HOLD_MS = 1500
private const val FILL_ALPHA = 0.35f
