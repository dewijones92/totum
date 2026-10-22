package com.dewijones92.totum.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

data class HoldToConfirmTexts(
    val title: String?,
    val body: String,
    val yes: String,
    val hint: String,
    val no: String,
)

@Composable
fun HoldToConfirmDialog(
    texts: HoldToConfirmTexts,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onDismiss: () -> Unit = onNo,
    yesTag: String = HOLD_DIALOG_YES_TAG,
    noTag: String = HOLD_DIALOG_NO_TAG,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = texts.title?.let { title -> { Text(title) } },
        text = { Text(texts.body) },
        confirmButton = {
            Button(
                onClick = onNo,
                modifier = Modifier.fillMaxWidth().height(NO_HEIGHT).testTag(noTag),
            ) { Text(texts.no, style = MaterialTheme.typography.titleMedium) }
        },
        dismissButton = {
            HoldToConfirmButton(
                label = texts.yes,
                hint = texts.hint,
                onConfirmed = onYes,
                modifier = Modifier.testTag(yesTag),
            )
        },
    )
}

@Composable
fun destructiveTexts(@PluralsRes body: Int, count: Int, @StringRes yes: Int): HoldToConfirmTexts = HoldToConfirmTexts(
    title = null,
    body = pluralStringResource(body, count, count),
    yes = stringResource(yes),
    hint = stringResource(R.string.hold_to_confirm),
    no = stringResource(R.string.keep_them),
)

class HoldToConfirmAsk {
    internal var pending by mutableStateOf<Pair<HoldToConfirmTexts, () -> Unit>?>(null)

    operator fun invoke(texts: HoldToConfirmTexts, go: () -> Unit) {
        pending = texts to go
    }
}

@Composable
fun rememberHoldToConfirmAsk(): HoldToConfirmAsk {
    val ask = remember { HoldToConfirmAsk() }
    ask.pending?.let { (texts, go) ->
        HoldToConfirmDialog(
            texts = texts,
            onYes = {
                ask.pending = null
                Diag.log("confirm", "held to confirm: ${texts.body}")
                go()
            },
            onNo = {
                ask.pending = null
                Diag.log("confirm", "said no: ${texts.body}")
            },
        )
    }
    return ask
}

const val HOLD_DIALOG_YES_TAG = "hold-dialog-yes"
const val HOLD_DIALOG_NO_TAG = "hold-dialog-no"
private val NO_HEIGHT = 56.dp
