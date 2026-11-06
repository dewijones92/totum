package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.update.WhatsNew
import com.dewijones92.totum.update.WhatsNewCard

const val WHATS_NEW_CARD_TAG = "whats-new-card"

@Composable
fun WhatsNewCardHost(whatsNew: WhatsNew?) {
    val card by (whatsNew?.card ?: return).collectAsStateWithLifecycle()
    card?.let { WhatsNewCard(it, onDismiss = whatsNew::dismiss) }
}

@Composable
fun WhatsNewCard(card: WhatsNewCard, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(WHATS_NEW_CARD_TAG),
        title = { Text(stringResource(R.string.whats_new_title, card.version)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_dismiss)) }
        },
    )
}
