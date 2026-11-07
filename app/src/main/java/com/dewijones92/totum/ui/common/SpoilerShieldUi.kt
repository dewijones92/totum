package com.dewijones92.totum.ui.common

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.spoiler.ShieldState
import com.dewijones92.totum.data.spoiler.ShieldedChannel
import com.dewijones92.totum.data.spoiler.SpoilerShield
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.NoShield
import com.dewijones92.totum.domain.statedChannel
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.playback.PlaybackState

internal val LocalItemShield = compositionLocalOf<ItemShield> { NoShield }

internal val LocalSpoilerShield = staticCompositionLocalOf<SpoilerShield?> { null }

private class ShieldView(private val shield: SpoilerShield, @Suppress("unused") val state: ShieldState) : ItemShield {
    override fun face(item: MediaItem): ItemFace = shield.face(item)
}

@Composable
internal fun ProvideSpoilerShield(container: AppContainer, content: @Composable () -> Unit) {
    val shield = container.aiFeatures.spoilerShield
    val state by shield.state.collectAsStateWithLifecycle()
    val view = remember(shield, state) { ShieldView(shield, state) }
    CompositionLocalProvider(LocalItemShield provides view, LocalSpoilerShield provides shield, content = content)
}

@Composable
internal fun MediaItem.face(): ItemFace = LocalItemShield.current.face(this)

@Composable
internal fun rememberResultHidden(state: PlaybackState): Boolean {
    val shield = LocalSpoilerShield.current ?: return false
    val shieldState by shield.state.collectAsStateWithLifecycle()
    return state.shielded && state.itemId.value !in shieldState.revealed
}

@Composable
internal fun rememberSetShieldedChannel(): (SpoilerShield, ShieldedChannel, Boolean) -> Unit {
    val context = LocalContext.current
    return { shield, channel, on ->
        shield.setChannel(channel, on)
        if (on && !HomeServerAuth.signedIn()) {
            Diag.warn("spoiler", "channel \"${channel.name}\" shielded with no signed-in home server: titles stay hidden")
            Toast.makeText(context, R.string.spoiler_no_home_server, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
internal fun SpoilerShieldActions(item: MediaItem?, onDismiss: () -> Unit) {
    val shield = LocalSpoilerShield.current ?: return
    if (item == null) return
    val setChannel = rememberSetShieldedChannel()
    val shielded = shield.isShielded(item)
    val revealed = shield.revealedByHand(item.id)
    val channel = item.statedChannel()?.youTubeChannelId to item.author
    val channelOn = shield.channelShielded(channel.first, channel.second)
    val name = item.author?.takeIf { it.isNotBlank() && (channel.first != null || channelOn) }
    SheetAction(
        when {
            shielded -> ({ shield.reveal(item.id) })
            revealed && channelOn -> ({ shield.hideAgain(item.id) })
            else -> null
        },
        Icons.Outlined.VisibilityOff,
        if (shielded) R.string.spoiler_reveal else R.string.spoiler_hide_again,
        onDismiss,
    )
    SheetAction(
        name?.let { { setChannel(shield, ShieldedChannel(channel.first, it), !channelOn) } },
        Icons.Outlined.VisibilityOff,
        if (channelOn) R.string.spoiler_channel_off else R.string.spoiler_channel_on,
        onDismiss,
    )
}

@Composable
internal fun SpoilerShieldToggle(channel: MediaSource.VideoChannel) {
    val shield = LocalSpoilerShield.current ?: return
    val setChannel = rememberSetShieldedChannel()
    val state by shield.state.collectAsStateWithLifecycle()
    val id = channel.youTubeChannelId
    val on = state.shieldsChannel(id, channel.title)
    IconButton(
        onClick = { setChannel(shield, ShieldedChannel(id, channel.title), !on) },
        modifier = Modifier.testTag(SPOILER_TOGGLE_TAG),
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.VisibilityOff else Icons.Outlined.VisibilityOff,
            contentDescription = stringResource(if (on) R.string.spoiler_channel_off else R.string.spoiler_channel_on),
        )
    }
}

const val SPOILER_TOGGLE_TAG = "spoilerShieldToggle"
