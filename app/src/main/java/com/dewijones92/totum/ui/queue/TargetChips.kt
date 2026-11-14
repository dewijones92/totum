package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.targets.TargetsRepository

internal fun Long.asColour(): Color = Color(toInt())

internal fun Target.chipLabel(power: TargetPower = TargetPower.ON): String {
    val name = "${kind.label.substringBefore(' ')} $label"
    return when (power) {
        TargetPower.ON -> name
        TargetPower.ASLEEP -> "$name 💤"
        TargetPower.WAKING -> "$name ⏰"
    }
}

@Composable
fun TargetChips(targets: TargetsRepository, modifier: Modifier = Modifier) {
    if (!targets.available) return
    val listed by targets.targets.collectAsStateWithLifecycle()
    val offered by targets.options.collectAsStateWithLifecycle()
    val states = listed.filter { state -> offered.any { it.id == state.target.id } }
    val selected by targets.selected.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(targets, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { targets.watchWhile() }
    }
    if (states.isEmpty()) return
    val power = states.associate { it.target.id to it.power }
    val inUse = states.filter { it.entries.isNotEmpty() || it.target.id == selected }.map { it.target }
    val idle = states.map { it.target } - inUse.toSet()
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetChip(
            label = stringResource(R.string.target_chip_phone),
            colour = TargetColours.of(colours, TargetColours.PHONE_ID).asColour(),
            selected = selected == null,
            onClick = { targets.select(null) },
        )
        inUse.forEach { target ->
            TargetChip(
                label = target.chipLabel(power[target.id] ?: TargetPower.ON),
                colour = TargetColours.of(colours, target.id).asColour(),
                selected = selected == target.id,
                onClick = { targets.select(target.id) },
            )
        }
        if (idle.isNotEmpty()) {
            TargetChip(
                label = stringResource(R.string.target_chip_more),
                colour = Color.Gray,
                selected = false,
                onClick = { picking = true },
            )
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                val idleOptions = idle.map { PlayOnOption(it, power[it.id] ?: TargetPower.ON) }
                PlayOnMenuItems(idleOptions, colours, selected) {
                    picking = false
                    targets.select(it.target.id)
                }
            }
        }
    }
}

@Composable
internal fun TargetChip(label: String, colour: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        border = BorderStroke(if (selected) SELECTED_BORDER else IDLE_BORDER, colour),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colour.copy(alpha = SELECTED_ALPHA)),
    )
}

private val SELECTED_BORDER = 2.dp
private val IDLE_BORDER = 1.dp
private const val SELECTED_ALPHA = 0.25f
