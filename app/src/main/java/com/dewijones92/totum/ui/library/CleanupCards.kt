package com.dewijones92.totum.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CleanupGroup
import com.dewijones92.totum.domain.CleanupRules
import com.dewijones92.totum.domain.CleanupSuggestion
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.formatBytes

const val CLEANUP_CARD_TAG = "cleanup-card"
const val CLEANUP_DELETE_TAG = "cleanup-delete"
const val CLEANUP_ROW_TAG = "cleanup-row"

class Cleanup(
    val suggestions: List<CleanupSuggestion> = emptyList(),
    val delete: (CleanupGroup, List<MediaItemId>) -> Unit = { _, _ -> },
    val rules: CleanupRules = CleanupRules(),
)

fun LazyListScope.cleanupSection(cleanup: Cleanup, onReview: (CleanupGroup) -> Unit) {
    if (cleanup.suggestions.isEmpty()) return
    item {
        Text(
            stringResource(R.string.cleanup_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    items(cleanup.suggestions, key = { "cleanup-${it.group}" }) { suggestion ->
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable {
                    Diag.log("library", "clean-up ${suggestion.group} opened: ${suggestion.count} file(s)")
                    onReview(suggestion.group)
                }
                .testTag("$CLEANUP_CARD_TAG-${suggestion.group}"),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.cleanup_free, formatBytes(suggestion.bytes)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        groupLabel(suggestion, cleanup.rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun groupLabel(suggestion: CleanupSuggestion, rules: CleanupRules): String {
    val n = suggestion.count
    return when (suggestion.group) {
        CleanupGroup.NOT_OPENED ->
            pluralStringResource(R.plurals.cleanup_not_opened, n, n, rules.notOpenedFor.inWholeDays.toInt())
        CleanupGroup.FINISHED -> pluralStringResource(R.plurals.cleanup_finished, n, n)
        CleanupGroup.BIG -> pluralStringResource(R.plurals.cleanup_big, n, n, formatBytes(rules.bigOverBytes))
        CleanupGroup.NOT_QUEUED -> pluralStringResource(R.plurals.cleanup_not_queued, n, n)
    }
}

@Composable
fun CleanupReview(
    suggestion: CleanupSuggestion,
    entries: List<LibraryViewModel.Entry>,
    rules: CleanupRules,
    onDelete: (List<MediaItemId>) -> Unit,
    onDismiss: () -> Unit,
) {
    val byId = remember(entries) { entries.associateBy { it.item.id } }
    val listed = suggestion.ids.mapNotNull(byId::get)
    var ticked by remember(suggestion.group) { mutableStateOf(listed.map { it.item.id }.toSet()) }
    val chosen = listed.filter { it.item.id in ticked }
    val bytes = chosen.sumOf { it.sizeBytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(groupLabel(suggestion, rules)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.cleanup_review_hint), style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(listed, key = { it.item.id.value }) { entry ->
                        val id = entry.item.id
                        val toggle = { ticked = if (id in ticked) ticked - id else ticked + id }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle)
                                .padding(vertical = 4.dp)
                                .testTag("$CLEANUP_ROW_TAG-${id.value}"),
                        ) {
                            Checkbox(checked = id in ticked, onCheckedChange = { toggle() })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.item.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDelete(chosen.map { it.item.id }) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.testTag(CLEANUP_DELETE_TAG),
            ) { Text(pluralStringResource(R.plurals.cleanup_delete, chosen.size, chosen.size, formatBytes(bytes))) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cleanup_cancel)) } },
    )
}

@Composable
fun CleanupReviewOf(
    cleanup: Cleanup,
    reviewing: CleanupGroup?,
    entries: List<LibraryViewModel.Entry>,
    onClose: () -> Unit,
) {
    val suggestion = cleanup.suggestions.firstOrNull { it.group == reviewing } ?: return
    CleanupReview(suggestion, entries, cleanup.rules, onDismiss = onClose, onDelete = { ids ->
        Diag.log("library", "clean-up ${suggestion.group}: ${ids.size} ticked, ${suggestion.count - ids.size} kept")
        cleanup.delete(suggestion.group, ids)
        onClose()
    })
}
