package com.dewijones92.totum.reminders.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun <T> ChoiceChips(
    title: String,
    choices: List<T>,
    chosen: T?,
    tag: String,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == chosen,
                onClick = { onChoose(choice) },
                label = { Text(label(choice)) },
                modifier = Modifier.testTag("$tag-$choice"),
            )
        }
    }
}
