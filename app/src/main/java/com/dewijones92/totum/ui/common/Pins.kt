package com.dewijones92.totum.ui.common

import android.content.Context
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
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.music.artistShuffle
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinAction
import com.dewijones92.totum.pins.PinOutcome
import com.dewijones92.totum.pins.PinPlayed
import com.dewijones92.totum.pins.PinTileActions
import com.dewijones92.totum.stats.UseKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface PinActions {
    val pinned: List<Pin>
    fun isPinned(key: String): Boolean = pinned.any { it.key == key }
    fun toggle(pin: Pin)
    fun addToHomeScreen(pin: Pin, lockOnOpen: Boolean = false)
    fun play(pin: Pin)
    fun run(pin: Pin, action: PinAction, onOutcome: (PinOutcome) -> Unit) = Unit
}

internal val LocalPins = compositionLocalOf<PinActions?> { null }

data class SheetExtras(
    val onStartRadio: (() -> Unit)? = null,
    val pin: Pin? = null,
    val onGoToAlbum: (() -> Unit)? = null,
    val music: Boolean = false,
    val item: MediaItem? = null,
)

@Composable
internal fun sheetExtrasFor(item: MediaItem, onStartRadio: (() -> Unit)?): SheetExtras = SheetExtras(
    onStartRadio = onStartRadio,
    pin = item.toPlayableOrNull()?.let(Pin::of),
    onGoToAlbum = LocalItemActions.current?.albumLink(item),
    music = item.contentKind == MediaContentKind.MUSIC,
    item = item,
)

@Composable
internal fun rememberPinActions(container: AppContainer): PinActions {
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val expandPlayer = LocalExpandPlayer.current
    return ContainerPinActions(container, context, expandPlayer, pins)
}

private class ContainerPinActions(
    private val container: AppContainer,
    private val context: Context,
    private val expandPlayer: () -> Unit,
    override val pinned: List<Pin>,
) : PinActions {

    private fun say(res: Int, title: String) =
        Toast.makeText(context, context.getString(res, title), Toast.LENGTH_SHORT).show()

    override fun toggle(pin: Pin) {
        if (isPinned(pin.key)) {
            container.pinStore.unpin(pin.key)
            say(R.string.pin_removed, pin.title)
        } else {
            container.pinStore.pin(pin)
            container.statsLog.use(UseKind.PIN, pin.key)
            say(R.string.pin_added, pin.title)
        }
    }

    override fun addToHomeScreen(pin: Pin, lockOnOpen: Boolean) {
        container.pinStore.pin(pin)
        val shortcuts = container.homeScreenShortcuts
        if (shortcuts == null) {
            say(R.string.pin_added, pin.title)
            return
        }
        container.applicationScope.launch {
            if (!shortcuts.requestPin(pin, lockOnOpen)) say(R.string.pin_home_unsupported, pin.title)
        }
    }

    private val tileActions by lazy {
        PinTileActions(
            player = container.pinPlayer,
            queue = container.playbackQueue,
            catalogue = container.musicCatalogue,
            playlists = container.youTubePlaylists,
            radio = container.musicRadio,
            favourites = container.musicFavourites,
            artistShuffle = container.artistShuffle(),
            recent = container.recentActions,
        )
    }

    override fun run(pin: Pin, action: PinAction, onOutcome: (PinOutcome) -> Unit) {
        when (action) {
            PinAction.TOGGLE_PIN -> return toggle(pin)
            PinAction.ADD_TO_HOME -> return addToHomeScreen(pin)
            PinAction.ADD_TO_HOME_LOCKED -> return addToHomeScreen(pin, lockOnOpen = true)
            else -> Unit
        }
        container.applicationScope.launch {
            val outcome = tileActions.run(pin, action)
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is PinOutcome.Done -> Toast.makeText(
                        context,
                        "✅ ${pin.title}: ${outcome.what}",
                        Toast.LENGTH_SHORT
                    ).show()
                    is PinOutcome.Failed ->
                        Toast.makeText(context, "❌ ${pin.title}: ${outcome.why}", Toast.LENGTH_LONG).show()
                    is PinOutcome.Open, is PinOutcome.OpenSource -> Unit
                }
                onOutcome(outcome)
            }
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
