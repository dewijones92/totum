package com.dewijones92.totum.ui.common

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinPlayed
import kotlinx.coroutines.launch

interface PinActions {
    val pinned: List<Pin>
    fun isPinned(key: String): Boolean = pinned.any { it.key == key }
    fun toggle(pin: Pin)
    fun addToHomeScreen(pin: Pin)
    fun play(pin: Pin)
}

internal val LocalPins = compositionLocalOf<PinActions?> { null }

data class SheetExtras(val onStartRadio: (() -> Unit)? = null, val pin: Pin? = null)

@Composable
internal fun rememberPinActions(container: AppContainer): PinActions {
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val expandPlayer = LocalExpandPlayer.current
    fun say(res: Int, title: String) = Toast.makeText(context, context.getString(res, title), Toast.LENGTH_SHORT).show()
    return object : PinActions {
        override val pinned: List<Pin> = pins

        override fun toggle(pin: Pin) {
            if (isPinned(pin.key)) {
                container.pinStore.unpin(pin.key)
                say(R.string.pin_removed, pin.title)
            } else {
                container.pinStore.pin(pin)
                say(R.string.pin_added, pin.title)
            }
        }

        override fun addToHomeScreen(pin: Pin) {
            container.pinStore.pin(pin)
            val shortcuts = container.homeScreenShortcuts
            if (shortcuts == null) {
                say(R.string.pin_added, pin.title)
                return
            }
            container.applicationScope.launch {
                if (!shortcuts.requestPin(pin)) say(R.string.pin_home_unsupported, pin.title)
            }
        }

        override fun play(pin: Pin) {
            container.applicationScope.launch {
                when (val played = container.pinPlayer.play(pin, from = "pinned row")) {
                    is PinPlayed.Started -> if (played.showsPicture) expandPlayer()
                    is PinPlayed.Failed -> {
                        Diag.warn("pin", "${pin.key} did not play: ${played.why}")
                        say(R.string.pin_could_not_play, pin.title)
                    }
                }
            }
        }
    }
}

@Composable
fun PinToggle(pin: Pin) {
    val pins = LocalPins.current ?: return
    val pinned = pins.isPinned(pin.key)
    IconButton(onClick = { pins.toggle(pin) }) {
        Icon(
            imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = stringResource(if (pinned) R.string.pin_unpin else R.string.pin_pin),
        )
    }
}
