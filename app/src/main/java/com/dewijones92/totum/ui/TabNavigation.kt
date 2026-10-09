package com.dewijones92.totum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.music.MusicPageScreen
import com.dewijones92.totum.ui.search.SearchScreen

@Stable
internal class TabNavigation(searching: Boolean = false) {
    var showSearch by mutableStateOf(searching)
        private set
    val pages = mutableStateListOf<MusicPage>()

    fun openSearch(over: TopLevelDestination) {
        Diag.log("nav", "search opened over $over (music pages open=${pages.size})")
        pages.clear()
        showSearch = true
    }

    fun closeSearch() {
        Diag.log("nav", "search closed")
        showSearch = false
    }

    fun open(page: MusicPage) {
        Diag.log("nav", "music page ${page.label} over ${pages.size} others, search=$showSearch")
        pages.add(page)
    }

    fun back() {
        val closed = pages.removeLastOrNull() ?: return
        Diag.log("nav", "closed music page ${closed.label}; ${pages.size} left")
    }

    fun <T> choosing(select: (T) -> Unit): (T) -> Unit = { chosen ->
        tabChosen()
        select(chosen)
    }

    fun searchOpener(over: TopLevelDestination): () -> Unit = { openSearch(over) }

    fun pageOpener(onOpened: () -> Unit): (MusicPage) -> Unit = { page ->
        open(page)
        onOpened()
    }

    fun tabChosen() {
        if (showSearch || pages.isNotEmpty()) {
            Diag.log("nav", "tab chosen: closing search=$showSearch and ${pages.size} music page(s)")
        }
        showSearch = false
        pages.clear()
    }

    companion object {
        val Saver: Saver<TabNavigation, Boolean> = Saver(save = { it.showSearch }, restore = { TabNavigation(it) })
    }
}

@Composable
internal fun rememberTabNavigation(): TabNavigation = rememberSaveable(saver = TabNavigation.Saver) { TabNavigation() }

@Composable
internal fun TabOverlays(container: AppContainer, tabs: TabNavigation) {
    BackHandler(enabled = tabs.showSearch && tabs.pages.isEmpty()) { tabs.closeSearch() }
    if (tabs.showSearch) {
        Surface(Modifier.fillMaxSize()) { SearchScreen(container, onBack = tabs::closeSearch) }
    }
    BackHandler(enabled = tabs.pages.isNotEmpty()) { tabs.back() }
    tabs.pages.lastOrNull()?.let { page ->
        key(page) {
            Surface(Modifier.fillMaxSize()) { MusicPageScreen(container, page, onBack = tabs::back) }
        }
    }
}
