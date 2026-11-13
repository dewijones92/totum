package com.dewijones92.totum.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.live.LiveRefresher
import com.dewijones92.totum.live.liveTagText
import com.dewijones92.totum.video.LiveState
import com.dewijones92.totum.video.LiveTag
import com.dewijones92.totum.video.liveTagOf
import com.dewijones92.totum.video.videoIdOf

internal val LocalLiveStates =
    staticCompositionLocalOf<State<Map<String, LiveState>>> { mutableStateOf(emptyMap()) }

internal val LocalLiveRefresher = staticCompositionLocalOf<LiveRefresher?> { null }

@Composable
internal fun liveTagFor(id: MediaItemId, kind: MediaContentKind, keepFresh: Boolean = true): LiveTag? {
    val held = LocalLiveStates.current
    val videoId = remember(id) { videoIdOf(id) }
    val tag = remember(held, videoId, kind) {
        derivedStateOf { liveTagOf(kind, videoId?.let { held.value[it] }) }
    }.value
    val refresher = LocalLiveRefresher.current
    val keepsFresh = keepFresh && videoId != null && (tag is LiveTag.Live || tag is LiveTag.StartsSoon)
    if (keepsFresh && refresher != null) {
        DisposableEffect(videoId, refresher) {
            refresher.shown(videoId.orEmpty())
            onDispose { refresher.hidden(videoId.orEmpty()) }
        }
    }
    return tag
}

@Composable
internal fun liveNow(id: MediaItemId, kind: MediaContentKind): Boolean =
    liveTagFor(id, kind, keepFresh = false) == LiveTag.Live

@Composable
internal fun liveTagLabel(tag: LiveTag): String = LocalContext.current.liveTagText(tag)

@Composable
internal fun liveTagColor(tag: LiveTag): Color = when (tag) {
    LiveTag.WasLive -> MaterialTheme.colorScheme.outline
    LiveTag.Live, is LiveTag.StartsSoon -> MaterialTheme.colorScheme.error
}

@Composable
internal fun TagBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun LiveBadge(id: MediaItemId, kind: MediaContentKind, modifier: Modifier = Modifier) {
    val tag = liveTagFor(id, kind) ?: return
    TagBadge(liveTagLabel(tag), liveTagColor(tag), modifier)
}
