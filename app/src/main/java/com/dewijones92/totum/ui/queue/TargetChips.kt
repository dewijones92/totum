package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetConnection
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.connection
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
    val address by targets.browserAddress.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(targets, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { targets.watchWhile() }
    }
    if (states.isEmpty() && address == null) return
    val power = states.associate { it.target.id to it.power }
    val connection = states.associate { it.target.id to it.connection }
    val doing = doingLines(states)
    val switchable = states.filter { it.canSwitchOff && it.power != TargetPower.ASLEEP }.map { it.target.id }.toSet()
    val inUse = states.filter { it.entries.isNotEmpty() || it.target.id == selected }.map { it.target }
    val idle = states.map { it.target } - inUse.toSet()
    var adding by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Box(modifier) {
        Row(
            modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColouredChip(
                targets = targets,
                id = TargetColours.PHONE_ID,
                label = stringResource(R.string.target_chip_phone),
                colour = TargetColours.of(colours, TargetColours.PHONE_ID),
                selected = selected == null,
                connection = null,
                onClick = { targets.select(null) },
            )
            inUse.forEach { target ->
                ColouredChip(
                    targets = targets,
                    id = target.id,
                    label = target.chipLabel(power[target.id] ?: TargetPower.ON),
                    colour = TargetColours.of(colours, target.id),
                    selected = selected == target.id,
                    connection = connection[target.id],
                    doing = doing[target.id],
                    onClick = { targets.select(target.id) },
                )
                if (selected == target.id && target.id in switchable) {
                    TargetChip(
                        label = stringResource(R.string.target_switch_off),
                        colour = Color.Gray,
                        selected = false,
                        onClick = { targets.command(target.id, TargetAction.SWITCH_OFF) },
                    )
                }
            }
            if (idle.isNotEmpty() || address != null) {
                MoreDevices(targets, offered, idle, colours, selected, address, doing) { adding = true }
            }
        }
        ScrollHints(scroll)
    }
    address?.takeIf { adding }?.let { AddBrowserDialog(it) { adding = false } }
}

@Composable
private fun MoreDevices(
    targets: TargetsRepository,
    offered: List<PlayOnOption>,
    idle: List<Target>,
    colours: Map<String, Long>,
    selected: String?,
    address: String?,
    doing: Map<String, String>,
    onAddBrowser: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    TargetChip(
        label = stringResource(R.string.target_chip_more),
        colour = Color.Gray,
        selected = false,
        onClick = { picking = true },
    )
    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
        val idleIds = idle.map { it.id }.toSet()
        PlayOnMenuItems(offered.filter { it.id in idleIds }, colours, selected, doing) {
            picking = false
            targets.choose(it)
        }
        address?.let {
            AddBrowserEntry({
                picking = false
                onAddBrowser()
            })
        }
    }
}

@Composable
private fun ColouredChip(
    targets: TargetsRepository,
    id: String,
    label: String,
    colour: Long,
    selected: Boolean,
    connection: TargetConnection?,
    onClick: () -> Unit,
    doing: String? = null,
) {
    var recolouring by remember { mutableStateOf(false) }
    Box {
        TargetChip(
            label = label,
            colour = colour.asColour(),
            selected = selected,
            onClick = onClick,
            connection = connection,
            doing = doing,
            onLongClick = {
                Diag.log("targets", "$id chip long-pressed: its colours open (now ${colour.toString(HEX)})")
                recolouring = true
            },
            longClickLabel = stringResource(R.string.target_colour_long_press),
        )
        TargetColourMenu(
            expanded = recolouring,
            onDismiss = { recolouring = false },
            label = label,
            current = colour,
            onPick = { targets.recolour(id, it) },
        )
    }
}

@Composable
private fun BoxScope.ScrollHints(scroll: ScrollState) {
    val fade = MaterialTheme.colorScheme.background
    if (scroll.canScrollBackward) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier.width(HINT_WIDTH).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(fade, Color.Transparent))),
                contentAlignment = Alignment.CenterStart,
            ) { Icon(Icons.Outlined.ChevronLeft, contentDescription = null, modifier = Modifier.size(HINT_ICON)) }
        }
    }
    if (scroll.canScrollForward) {
        val more = stringResource(R.string.target_more_devices)
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.width(HINT_WIDTH).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, fade)))
                    .testTag(CHIPS_MORE_TAG),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Outlined.ChevronRight, contentDescription = more, modifier = Modifier.size(HINT_ICON)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TargetChip(
    label: String,
    colour: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    connection: TargetConnection? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    doing: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) colour.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
        border = BorderStroke(if (selected) SELECTED_BORDER else IDLE_BORDER, colour),
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .combinedClickable(
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = longClickLabel,
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            connection?.let { link ->
                Box(Modifier.size(DOT).background(link.dotColour(), CircleShape).testTag(CHIP_DOT_TAG))
            }
            Column(Modifier.widthIn(max = CHIP_MAX_WIDTH)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                doing?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CHIP_DOING_TAG),
                    )
                }
            }
        }
    }
}

const val CHIPS_MORE_TAG = "target-chips-more"
const val CHIP_DOT_TAG = "target-chip-dot"
const val CHIP_DOING_TAG = "target-chip-doing"
private val CHIP_MAX_WIDTH = 220.dp
private val SELECTED_BORDER = 2.dp
private val IDLE_BORDER = 1.dp
private const val SELECTED_ALPHA = 0.25f
private val DOT = 8.dp
private val HINT_WIDTH = 28.dp
private val HINT_ICON = 20.dp
private const val HEX = 16
