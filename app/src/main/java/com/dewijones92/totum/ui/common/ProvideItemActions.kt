package com.dewijones92.totum.ui.common

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.ShareLink
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.domain.messageFor
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.domain.shareLink
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.music.ArtistCredits
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.ui.player.LocalTrail
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
    val resources = LocalResources.current
    val offers = LocalSnackbarOffers.current
    val copier = rememberCopier()
    val sharer = rememberSharer()
    val links = remember(copier, sharer) { ItemLinks(copier, sharer) }
    val actions = remember(container, rowActions, scope, onOpenSource, openMusicPage, offers, resources, links) {
        ContainerItemActions(container, rowActions, scope, onOpenSource, openMusicPage, offers, resources, links)
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
        LocalDownloadLocking provides container.lockingDownloads,
        LocalHomeDownloads provides container.homeDownloads,
        LocalTrail provides container.trail,
        LocalReadyAhead provides remember(container) {
            {
                    item: MediaItem ->
                container.readyAhead.ready(item.toPlayableOrNull(), "menu opened")
            }
        },
        LocalOpenSource provides openSource,
        LocalStartRadio provides remember(container) { { item: MediaItem -> startRadio(container, item) } },
        LocalStartRadioMix provides remember(container) {
            {
                    items: List<MediaItem> ->
                startRadioMix(container, items)
            }
        },
        LocalPins provides rememberPinActions(container),
        LocalRecent provides container.recentActions,
        LocalFavourites provides remember(container) {
            FavouritesHost(container.musicFavourites, container.applicationScope)
        },
        LocalStars provides remember(container) {
            StarHost(container.stars, container.musicFavourites, container.applicationScope)
        },
        content = content,
    )
}

@Suppress("TooManyFunctions", "LongParameterList")
private class ContainerItemActions(
    private val container: AppContainer,
    private val rows: MediaItemActions,
    private val scope: CoroutineScope,
    private val onOpenSource: (MediaSource) -> Unit,
    private val openMusicPage: ((MusicPage) -> Unit)?,
    private val offers: SnackbarOffers?,
    private val resources: Resources,
    private val links: ItemLinks,
) : ItemActions {
    private val credits = ArtistCredits(container.musicCatalogue, container.operations) { title ->
        resources.getString(R.string.finding_artists, title)
    }

    override fun queue(items: List<MediaItem>, next: Boolean) = rows.queueAll(items, next)
    override fun addToPlaylist(items: List<MediaItem>) = rows.addToPlaylist(items)
    override fun peek(item: MediaItem) = rows.peek(item)

    override fun download(item: MediaItem, audioOnly: Boolean) {
        scope.launch { container.downloadManager.download(item, audioOnly) }
    }

    override fun downloadSome(items: List<MediaItem>, title: String) {
        if (container.downloadChooser.requestAllRemembered(items)) return
        val playable = items.mapNotNull { it.toPlayableOrNull() }
        Diag.log("download", "${items.size} selected to download (${playable.size} playable); asking how, in the sheet")
        container.episodeDownloads.ask(title, playable)
    }

    override val downloadChoice: DownloadChoice?
        get() = container.appPreferences.settings.value.downloadChoice

    override fun chooseDownload(item: MediaItem, pillar: MediaKind, askAnyway: Boolean, videoOnly: Boolean) {
        container.downloadChooser.request(item, pillar, askAnyway, videoOnly)
    }

    override fun deleteDownload(id: MediaItemId) {
        scope.launch { container.downloadManager.deleteSoon(id) }
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
                    if (credits.needsAsking(item)) {
                        scope.launch { credits.pageFor(item)?.let(open) }
                    } else {
                        open(artist)
                    }
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

    override fun notForMe(item: MediaItem): () -> Unit = {
        val queue = container.playbackQueue
        val entry = queue.state.value.entries.firstOrNull { it.item.item.id == item.id }
        val index = entry?.let(queue::remove)
        val queued = if (index != null) "left the queue from #${index + 1}" else "was not queued"
        container.notForMe.suppress(item, "the item menu; $queued")
        if (offers == null) {
            Diag.log("notforme", "dewidebug no snackbar host here, so no undo is offered for \"${item.title}\"")
        } else {
            offers.offer(
                resources.getString(R.string.not_for_me_done, item.title),
                resources.getString(R.string.undo),
                "notforme",
                SnackbarDuration.Long,
            ) {
                container.notForMe.allow(canonicalItemId(item.id.value), "undo")
                if (entry != null && index != null) queue.restoreAt(index, entry)
            }
        }
    }

    override fun share(item: MediaItem): () -> Unit = {
        val link = item.shareLink(HomeServerAuth.homeHost())
        Diag.log("share", "dewidebug sharing \"${item.title}\" [id=${item.id.value}]: ${link.why}")
        links.share(link.messageFor(item.title), item.title)
    }

    override fun copyLink(item: MediaItem): (() -> Unit)? {
        val link = item.shareLink(HomeServerAuth.homeHost())
        if (link !is ShareLink.Link) {
            Diag.log("share", "dewidebug no link to copy for \"${item.title}\" [id=${item.id.value}]: ${link.why}")
            return null
        }
        return {
            Diag.log("share", "dewidebug copying the link of \"${item.title}\" [id=${item.id.value}]: ${link.why}")
            links.copy(link.url.value, resources.getString(R.string.details_copy_link))
        }
    }

    override fun shareList(title: String, items: List<MediaItem>): (() -> Unit)? {
        if (items.isEmpty()) return null
        return {
            Diag.log(
                "share",
                "dewidebug sharing the list \"$title\": ${items.size} titles, no link because a group has none"
            )
            links.share(resources.shareListMessage(title, items), title)
        }
    }

    override fun watch(item: MediaItem) {
        rows.switchMode(item, toAudio = false, audioOnMessage = "", videoOnMessage = "", from = "player")
    }

    override fun switchMode(item: MediaItem) {
        // Labels come from the row; the mode change and its announcement live in MediaItemActions.
        rows.switchMode(item, toAudio = !rows.audioMode, audioOnMessage = "", videoOnMessage = "")
    }
}

internal class ItemLinks(val copy: (String, String) -> Unit, val share: (String, String) -> Unit)

private fun startRadioMix(container: AppContainer, items: List<MediaItem>) {
    val seeds = items.mapNotNull { item -> item.radioVideoId()?.let { it to item.title } }
    Diag.log("radio", "radio from ${items.size} selected item(s): ${seeds.size} with a YouTube id")
    if (seeds.isEmpty()) return
    container.applicationScope.launch { container.musicRadio.startMix(seeds) }
}

internal fun MediaItem.radioVideoId(): String? = mediaUrl?.youTubeVideoId() ?: HttpUrl.parse(id.value)?.youTubeVideoId()

private fun startRadio(container: AppContainer, item: MediaItem) {
    val videoId = item.radioVideoId()
    if (videoId == null) {
        Diag.warn("radio", "\"${item.title}\" has no YouTube id to start a radio from")
        return
    }
    container.applicationScope.launch { container.musicRadio.start(RadioSeed.forSong(videoId), item.title) }
}
