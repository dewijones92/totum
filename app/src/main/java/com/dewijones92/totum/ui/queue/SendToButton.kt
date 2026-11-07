package com.dewijones92.totum.ui.queue

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.targets.SendToTarget
import com.dewijones92.totum.targets.TargetsRepository

@Composable
fun SendToButton(sender: SendToTarget, targets: TargetsRepository, playingFromQueue: Boolean) {
    val states by targets.targets.collectAsStateWithLifecycle()
    if (!playingFromQueue || states.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.target_send_to)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        states.forEach { state ->
            DropdownMenuItem(
                text = { Text(state.target.chipLabel()) },
                onClick = {
                    open = false
                    sender.send(state.target.id)
                },
            )
        }
    }
}
