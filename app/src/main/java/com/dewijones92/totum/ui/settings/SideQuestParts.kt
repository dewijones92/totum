package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.theme.LocalLaurel
import kotlin.math.roundToInt

fun snapToStep(value: Float, range: IntRange, step: Int): Int {
    val steps = ((value - range.first) / step).roundToInt()
    return (range.first + steps * step).coerceIn(range)
}

fun stepsBetween(range: IntRange, step: Int): Int = ((range.last - range.first) / step - 1).coerceAtLeast(0)

@Composable
fun QuestCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val laurel = LocalLaurel.current
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = laurel.accent, modifier = Modifier.padding(end = 10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun SnappingSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    shown: (Int) -> String,
    tag: String,
    onCommit: (Int) -> Unit,
) {
    var held by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val laurel = LocalLaurel.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                shown(snapToStep(held, range, step)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("$tag-value"),
            )
        }
        Slider(
            value = held,
            onValueChange = { held = snapToStep(it, range, step).toFloat() },
            onValueChangeFinished = {
                val chosen = snapToStep(held, range, step)
                if (chosen != value) {
                    Diag.log("sidequest", "slider \"$label\" $value -> $chosen")
                    onCommit(chosen)
                }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = stepsBetween(range, step),
            colors = SliderDefaults.colors(
                thumbColor = laurel.accent,
                activeTrackColor = laurel.accent,
                inactiveTrackColor = laurel.container,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.testTag(tag),
        )
    }
}
