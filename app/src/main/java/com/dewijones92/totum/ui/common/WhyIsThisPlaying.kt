package com.dewijones92.totum.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.describe
import com.dewijones92.totum.playback.PlaybackState

@Composable
fun WhyIsThisPlayingDialog(state: PlaybackState, from: String, onDismiss: () -> Unit) {
    val explanation = state.playReason.explanation()
    LaunchedEffect(state.itemId, state.playReason) {
        Diag.log(
            "why",
            "dewidebug why-is-this-playing asked in the $from for ${state.itemId.value}: " +
                (state.playReason?.describe() ?: "no reason recorded"),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.why_playing_title)) },
        text = { Text(explanation, modifier = Modifier.testTag(WHY_PLAYING_TEXT_TAG)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
fun WhyIsThisPlayingMenu(expanded: Boolean, onDismiss: () -> Unit, onAsk: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.why_playing_title)) },
            onClick = {
                onDismiss()
                onAsk()
            },
            modifier = Modifier.testTag(WHY_PLAYING_MENU_TAG),
        )
    }
}

@Composable
internal fun PlayReason?.explanation(): String {
    val phrase = phrase()
    return phrase.detail?.let { stringResource(phrase.withDetail, it) } ?: stringResource(phrase.withoutDetail)
}

const val WHY_PLAYING_TEXT_TAG = "why-playing-text"
const val WHY_PLAYING_MENU_TAG = "why-playing-menu"
const val WHY_PLAYING_TITLE_TAG = "why-playing-title"
