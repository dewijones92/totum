package com.dewijones92.totum.exsurge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun ExsurgeStatusCard(
    view: ExsurgeView,
    onAction: (BannerAction) -> Unit,
    modifier: Modifier = Modifier,
    ticking: Boolean = true,
) {
    val now by produceState(view.at, ticking) {
        while (ticking) {
            value = Instant.now()
            delay(TICK_MS)
        }
    }
    val live = if (now.isAfter(view.at)) view.copy(at = now) else view
    val (title, text, progress) = BannerText(LocalContext.current).describe(live)
    Card(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("exsurge-status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("exsurge-status-title"),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("exsurge-status-detail")
            )
            progress?.let { (done, total) ->
                LinearProgressIndicator(
                    progress = { done.coerceIn(0, total).toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("exsurge-status-progress"),
                )
            }
            val actions = live.actions
            if (actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action ->
                        OutlinedButton(
                            onClick = { onAction(action) },
                            modifier = Modifier.testTag("exsurge-action-${action.name.lowercase()}"),
                        ) { Text(stringResource(action.label)) }
                    }
                }
            }
        }
    }
}

private const val TICK_MS = 1_000L
