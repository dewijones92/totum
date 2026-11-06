package com.dewijones92.totum.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.ui.common.FilterField
import com.dewijones92.totum.ui.common.NoFilterMatches
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rememberRecentSearches
import kotlinx.coroutines.delay

internal const val SETTINGS_RESULT_TAG = "settings-search-result"
private const val HIGHLIGHT_MS = 2_000L
private const val UNCLAIMED_MS = 1_500L

@Stable
internal class SettingsFocus {
    var target: SettingsRow? by mutableStateOf(null)
    var highlighted: SettingsRow? by mutableStateOf(null)
}

internal val LocalSettingsFocus = staticCompositionLocalOf { SettingsFocus() }

@Composable
internal fun Modifier.settingsRow(row: SettingsRow): Modifier {
    val focus = LocalSettingsFocus.current
    val requester = remember { BringIntoViewRequester() }
    val lit = focus.highlighted == row
    LaunchedEffect(focus.target) {
        if (focus.target != row) return@LaunchedEffect
        withFrameNanos { }
        focus.highlighted = row
        requester.bringIntoView()
        Diag.log("settings", "search: scrolled to ${row.name} and highlighted it")
        focus.target = null
    }
    LaunchedEffect(lit) {
        if (!lit) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        if (focus.highlighted == row) focus.highlighted = null
    }
    val tint by animateColorAsState(
        if (lit) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "settings row highlight",
    )
    return bringIntoViewRequester(requester).background(tint)
}

@Composable
internal fun AnchoredRow(row: SettingsRow, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().settingsRow(row)) { content() }
}

@Composable
internal fun ClearUnclaimedTarget(focus: SettingsFocus) {
    val target = focus.target
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        delay(UNCLAIMED_MS)
        if (focus.target == target) {
            Diag.log("settings", "search: ${target.name} was not on screen to scroll to, dropped")
            focus.target = null
        }
    }
}

@Composable
internal fun SettingsSearchResults(rows: List<SettingsRow>, onPick: (SettingsRow) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(row) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(SETTINGS_RESULT_TAG),
            ) {
                Text(stringResource(row.label), style = MaterialTheme.typography.bodyLarge)
                val where = stringResource(row.section)
                Text(
                    text = row.summary?.let { "$where · ${stringResource(it)}" } ?: where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SettingsSearchBox(
    focus: SettingsFocus,
    open: (SettingsSubPage?) -> Unit,
    sections: @Composable () -> Unit,
) {
    val resources = LocalResources.current
    val filter = rememberListFilter("settings")
    val recents = rememberRecentSearches("filter:settings")
    val shown = filter.filter(SettingsRow.entries, fields = { row ->
        listOf(
            resources.getString(row.label),
            resources.getString(row.section),
            row.summary?.let(resources::getString),
            row.keywords,
        )
    })
    ClearUnclaimedTarget(focus)
    FilterField(filter, shown.size, SettingsRow.entries.size)
    when {
        !filter.filtering -> sections()
        shown.isEmpty() -> NoFilterMatches(filter.query)
        else -> SettingsSearchResults(shown) { row ->
            Diag.log(
                "settings",
                "search \"${filter.query}\" picked ${row.name}: " +
                    (row.page?.let { "opens ${it.name}" } ?: "scrolls to the row"),
            )
            recents?.record(filter.query, "picked ${row.name}")
            filter.query = ""
            val page = row.page
            if (page != null) open(page) else focus.target = row
        }
    }
}
