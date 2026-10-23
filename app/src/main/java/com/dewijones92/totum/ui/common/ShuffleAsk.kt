package com.dewijones92.totum.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

object LastShuffleChoice {
    var randomStarts: Boolean = false
}

@Stable
class ShuffleAsk {
    internal var pending by mutableStateOf<((Boolean) -> Unit)?>(null)

    operator fun invoke(go: (randomStarts: Boolean) -> Unit) {
        pending = go
    }
}

@Composable
fun rememberShuffleAsk(): ShuffleAsk {
    val ask = remember { ShuffleAsk() }
    ask.pending?.let { go ->
        var randomStarts by remember { mutableStateOf(LastShuffleChoice.randomStarts) }
        AlertDialog(
            onDismissRequest = { ask.pending = null },
            title = { Text(stringResource(R.string.shuffle_ask_title)) },
            text = { RandomStartsCheckbox(randomStarts) { randomStarts = it } },
            confirmButton = {
                TextButton(
                    onClick = {
                        ask.pending = null
                        LastShuffleChoice.randomStarts = randomStarts
                        Diag.log("shuffle", "shuffle confirmed, random starts=$randomStarts")
                        go(randomStarts)
                    },
                    modifier = Modifier.testTag(SHUFFLE_ASK_GO_TAG),
                ) { Text(stringResource(R.string.shuffle_ask_go)) }
            },
            dismissButton = {
                TextButton(onClick = { ask.pending = null }) { Text(stringResource(R.string.shuffle_ask_cancel)) }
            },
        )
    }
    return ask
}

@Composable
fun RandomStartsCheckbox(checked: Boolean, onChange: (Boolean) -> Unit) {
    LabelledCheckbox(
        checked,
        stringResource(R.string.shuffle_random_starts),
        Modifier.testTag(RANDOM_STARTS_TAG),
        onChange
    )
}

const val SHUFFLE_ASK_GO_TAG = "shuffle-ask-go"
const val RANDOM_STARTS_TAG = "shuffle-random-starts"
