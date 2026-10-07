package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag

class FeedGate internal constructor(private val where: String) {
    var shown: Boolean by mutableStateOf(false)
        private set

    fun hidden(hideUntilAsked: Boolean): Boolean = hideUntilAsked && !shown

    fun show() {
        shown = true
        Diag.log("feed-gate", "$where shown: Show pressed")
    }
}

@Composable
fun rememberFeedGate(where: String, hideUntilAsked: Boolean): FeedGate {
    val gate = remember(where) { FeedGate(where) }
    DisposableEffect(gate) {
        Diag.log(
            "feed-gate",
            if (hideUntilAsked) "$where entered: hidden until asked" else "$where entered: shown, the setting is off",
        )
        onDispose {
            if (gate.shown) Diag.log("feed-gate", "$where left: hidden again on return")
        }
    }
    return gate
}

@Composable
fun ShowWhenAskedButton(label: String, note: String, onShow: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(onClick = onShow, modifier = Modifier.testTag(SHOW_FEED_TAG)) {
            Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(label)
        }
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

const val SHOW_FEED_TAG: String = "show-feed"
