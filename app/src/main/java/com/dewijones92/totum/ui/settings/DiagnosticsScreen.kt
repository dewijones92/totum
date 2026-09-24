package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.ui.common.FilterField
import com.dewijones92.totum.ui.common.rememberFiltered

/**
 * Shows what the app has been doing — the same running counts and event trail that a
 * crash report carries, readable before anything goes wrong.
 *
 * It exists because all of that was write-only: stalls were counted, timings recorded and
 * every notable event kept, but the only way to look was to send a report and read it off
 * the server. When something behaves oddly, the answer is usually already here.
 */
@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Snapshotted on entry: a trail that reordered itself while being read would be
    // useless, and "refresh" is a deliberate act.
    var taken by remember { mutableStateOf(0) }
    val vitals = remember(taken) { Vitals.snapshot().toList() }
    val events = remember(taken) { Breadcrumbs.snapshot().reversed() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { taken++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh))
            }
        }
        var query by rememberSaveable { mutableStateOf("") }
        val shownVitals = rememberFiltered("diagnostics-vitals", vitals, query) { listOf(it.first, it.second) }
        val shownEvents = rememberFiltered("diagnostics-events", events, query) { listOf(it.tag, it.message) }
        FilterField(query, { query = it }, shownVitals.size + shownEvents.size, vitals.size + events.size)
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { SectionLabel(stringResource(R.string.diagnostics_vitals)) }
            if (vitals.isEmpty()) {
                item { Hint(stringResource(R.string.diagnostics_empty)) }
            }
            items(shownVitals) { (name, value) -> VitalRow(name, value) }

            item { SectionLabel(stringResource(R.string.diagnostics_events, shownEvents.size)) }
            items(shownEvents) { entry ->
                EventRow(Breadcrumbs.formatTime(entry.atEpochMs), entry.tag, entry.message)
            }
        }
    }
}

@Composable
private fun VitalRow(name: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** Newest first: when something has just gone wrong, the relevant lines are the last ones. */
@Composable
private fun EventRow(at: String, tag: String, message: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "$at  [$tag]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
