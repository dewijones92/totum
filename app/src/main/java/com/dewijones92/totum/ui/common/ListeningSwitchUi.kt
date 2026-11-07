package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.playback.R as PlaybackR

class ListeningSwitchUi(val target: Listening?, val onTap: (String) -> Unit)

val LocalListeningSwitch = compositionLocalOf<ListeningSwitchUi?> { null }

@Composable
fun SwitchListeningButton(from: String, modifier: Modifier = Modifier) {
    val ui = LocalListeningSwitch.current ?: return
    val to = ui.target ?: return
    IconButton(onClick = { ui.onTap(from) }, modifier = modifier.testTag(switchListeningTag(from))) {
        Icon(
            if (to == Listening.MUSIC) Icons.Filled.MusicNote else Icons.Filled.Mic,
            contentDescription = stringResource(
                if (to == Listening.MUSIC) {
                    PlaybackR.string.playback_switch_to_music
                } else {
                    PlaybackR.string.playback_switch_to_talk
                },
            ),
        )
    }
}

@Composable
fun listeningSwitchUi(container: AppContainer): ListeningSwitchUi {
    val target by container.listeningSwitcher.target.collectAsStateWithLifecycle()
    val switcher = container.listeningSwitcher
    return remember(target, switcher) { ListeningSwitchUi(target, switcher::tap) }
}

fun switchListeningTag(from: String): String = "switch-listening-$from"
