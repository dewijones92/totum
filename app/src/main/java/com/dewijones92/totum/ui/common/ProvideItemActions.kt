package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.music.RadioSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the app-wide [ItemActions] and provides it to every row beneath.
 *
 * [onOpenSource] is the one genuinely screen-shaped piece — where "go to channel/podcast" lands —
 * so the shell supplies it once instead of each screen hosting its own channel overlay.
 */
@Composable
internal fun ProvideItemActions(
    container: AppContainer,
    onOpenSource: (MediaSource) -> Unit,
    content: @Composable () -> Unit,
) {
    val rowActions = rememberMediaItemActions(container)
    val scope = rememberCoroutineScope()
    val openMusicPage = LocalOpenMusicPage.current
    val actions = remember(container, rowActions, scope, onOpenSource, openMusicPage) {
        ContainerItemActions(container, rowActions, scope, onOpenSource, openMusicPage)
    }
    val openSource: (MediaSource) -> Unit = remember(onOpenSource) {
        {
                source ->
            Diag.log("nav", "open source \"${source.title}\" [pillar=${source.pillar} id=${source.id.value}]")
            onOpenSource(source)
        }
    }
    CompositionLocalProvider(
        LocalItemActions provides actions,
        LocalReadyAhead provides { item -> container.readyAhead.ready(item.toPlayableOrNull(), "menu opened") },
        LocalOpenSource provides openSource,
        LocalStartRadio provides { item -> startRadio(container, item) },
        LocalPins provides rememberPinActions(container),
        content = content,
    )
}

private class ContainerItemActions(
    private val container: AppContainer,
    private val rows: MediaItemActions,
    private val scope: CoroutineScope,
    private val onOpenSource: (MediaSource) -> Unit,
    private val openMusicPage: ((MusicPage) -> Unit)?,
) : ItemActions {
    override fun queue(items: List<MediaItem>, next: Boolean) = rows.queueAll(items, next)
    override fun addToPlaylist(items: List<MediaItem>) = rows.addToPlaylist(items)
    override fun peek(item: MediaItem) = rows.peek(item)

    override fun download(item: MediaItem, audioOnly: Boolean) {
        scope.launch { container.downloadManager.download(item, audioOnly) }
    }

    override fun deleteDownload(id: MediaItemId) {
        scope.launch { container.downloadManager.delete(id) }
    }

    override fun setPlayed(id: MediaItemId, played: Boolean) {
        scope.launch { container.playbackProgressStore.setPlayed(id, played) }
    }

    override fun sourceLink(item: MediaItem): (() -> Unit)? {
        val open = openMusicPage
        val artist = item.artistPage()
        return when {
            open != null && artist != null -> {
                {
                    Diag.log("nav", "go to ${artist.label} from \"${item.title}\"")
                    open(artist)
                }
            }
            container.sourceLocator.canLocate(item) -> { { rows.goToSource(item, onOpenSource) } }
            else -> null
        }
    }

    override fun albumLink(item: MediaItem): (() -> Unit)? {
        val open = openMusicPage ?: return null
        val album = item.albumPage() ?: return null
        return {
            Diag.log("nav", "go to ${album.label} from \"${item.title}\"")
            open(album)
        }
    }

    override val audioMode: Boolean get() = rows.audioMode

    override fun watch(item: MediaItem) {
        rows.switchMode(item, toAudio = false, audioOnMessage = "", videoOnMessage = "", from = "player")
    }

    override fun switchMode(item: MediaItem) {
        // Labels come from the row; the mode change and its announcement live in MediaItemActions.
        rows.switchMode(item, toAudio = !rows.audioMode, audioOnMessage = "", videoOnMessage = "")
    }
}

private fun startRadio(container: AppContainer, item: MediaItem) {
    val videoId = item.mediaUrl?.youTubeVideoId() ?: HttpUrl.parse(item.id.value)?.youTubeVideoId()
    if (videoId == null) {
        Diag.warn("radio", "\"${item.title}\" has no YouTube id to start a radio from")
        return
    }
    container.applicationScope.launch { container.musicRadio.start(RadioSeed.forSong(videoId), item.title) }
}
