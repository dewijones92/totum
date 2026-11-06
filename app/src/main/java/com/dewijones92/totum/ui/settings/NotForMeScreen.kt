package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.data.notforme.SuppressedItem
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo

@Composable
fun NotForMeScreen(notForMe: NotForMe, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val items by notForMe.items.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val allow = rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "notforme",
        duration = SnackbarDuration.Long,
        removedMessage = { gone: SuppressedItem -> resources.getString(R.string.not_for_me_allowed, gone.title) },
        remove = { id: String -> items.firstOrNull { it.id == id }?.also { notForMe.allow(id, "the settings list") } },
        restore = { notForMe.mergeMissing(listOf(it)) },
    )
    Surface(modifier = modifier.fillMaxSize()) {
        Column {
            BackHeader(stringResource(R.string.not_for_me_settings_row), onBack)
            Text(
                stringResource(R.string.not_for_me_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.not_for_me_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { gone -> NotForMeRow(gone) { allow(gone.id) } }
                }
            }
        }
    }
}

@Composable
private fun NotForMeRow(gone: SuppressedItem, onAllow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(gone.title, style = MaterialTheme.typography.bodyLarge)
            gone.by?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onAllow) { Text(stringResource(R.string.not_for_me_allow)) }
    }
}
