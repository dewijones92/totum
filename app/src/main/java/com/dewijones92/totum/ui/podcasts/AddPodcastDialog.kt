package com.dewijones92.totum.ui.podcasts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.podcasts.PodcastsViewModel.Subscribing

/**
 * URL-entry dialog for subscribing to a feed. Closes itself when the
 * subscription lands ([Subscribing.Done]).
 */
@Composable
internal fun AddPodcastDialog(
    subscribing: Subscribing,
    onSubscribe: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(subscribing) {
        if (subscribing == Subscribing.Done) onDismiss()
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_podcast)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.feed_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (subscribing) {
                    is Subscribing.Error -> Text(
                        text = stringResource(subscribing.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Subscribing.InProgress -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 8.dp).size(20.dp),
                    )
                    Subscribing.Idle, Subscribing.Done -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubscribe(url) },
                enabled = subscribing != Subscribing.InProgress,
            ) { Text(stringResource(R.string.subscribe)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal fun Subscribing.Error.messageRes(): Int = when (this) {
    Subscribing.Error.InvalidUrl -> R.string.error_invalid_url
    Subscribing.Error.Network -> R.string.error_network
    Subscribing.Error.InvalidFeed -> R.string.error_invalid_feed
    Subscribing.Error.AlreadySubscribed -> R.string.error_already_subscribed
}
