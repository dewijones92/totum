package com.dewijones92.totum.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.ui.common.ActionSheet
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.SheetExtras

@Composable
internal fun TorrentMenuSheet(search: SearchViewModel, container: AppContainer) {
    val menu by search.torrentMenu.collectAsStateWithLifecycle()
    val chosen = menu ?: return
    val single = chosen.items.singleOrNull()
    if (single != null) {
        ItemActionSheet(single.item, onDismiss = search::closeTorrentMenu, pillar = single.handle.pillar)
        return
    }
    val actions = LocalItemActions.current
    ActionSheet(
        title = pluralStringResource(R.plurals.torrent_menu_title, chosen.items.size, chosen.title, chosen.items.size),
        onPlayInsteadOfCurrent = null,
        onPlayNext = { search.queueTorrent(next = true) },
        onAddToQueue = { search.queueTorrent(next = false) },
        onAddToPlaylist = actions?.let { a -> { a.addToPlaylist(chosen.items.map { it.item }) } },
        onRemoveFromPlaylist = null,
        onRemoveFromQueue = null,
        onPeek = null,
        onDownloadVideo = null,
        onDownload = { container.episodeDownloads.ask(chosen.title, chosen.items) },
        onDeleteDownload = null,
        onSwitchMode = null,
        audioMode = false,
        onGoToSource = null,
        sourcePillar = MediaKind.VIDEO,
        onMoveToTop = null,
        onMoveToBottom = null,
        onSetPlayed = actions?.let { a -> { played -> chosen.items.forEach { a.setPlayed(it.item.id, played) } } },
        played = false,
        onDismiss = search::closeTorrentMenu,
        extras = SheetExtras(onShareList = actions?.shareList(chosen.title, chosen.items.map { it.item })),
    )
}
