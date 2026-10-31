package com.dewijones92.totum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.ui.ask.AskRequests
import com.dewijones92.totum.ui.ask.AskScreen
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.music.MusicPageScreen
import com.dewijones92.totum.ui.search.SearchScreen

@Stable
internal class TabNavigation(searching: Boolean = false, asking: Boolean = false) {
    var showSearch by mutableStateOf(searching)
        private set
    var showAsk by mutableStateOf(asking)
        private set
    val pages = mutableStateListOf<MusicPage>()
    var onSearchOpened: () -> Unit = {}

    fun openSearch(over: TopLevelDestination) {
        Diag.log("nav", "search opened over $over (music pages open=${pages.size})")
        pages.clear()
        showSearch = true
        onSearchOpened()
    }

    fun openAsk(from: String) {
        Diag.log("nav", "ask opened from $from (search=$showSearch, music pages open=${pages.size})")
        pages.clear()
        showSearch = false
        showAsk = true
    }

    fun closeAsk() {
        Diag.log("nav", "ask closed")
        showAsk = false
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
        if (showSearch || showAsk || pages.isNotEmpty()) {
            Diag.log("nav", "tab chosen: closing search=$showSearch ask=$showAsk and ${pages.size} music page(s)")
        }
        showSearch = false
        showAsk = false
        pages.clear()
    }

    companion object {
        val Saver: Saver<TabNavigation, List<Boolean>> = Saver(
            save = { listOf(it.showSearch, it.showAsk) },
            restore = { TabNavigation(searching = it[0], asking = it.getOrElse(1) { false }) },
        )
    }
}

@Composable
internal fun rememberTabNavigation(): TabNavigation = rememberSaveable(saver = TabNavigation.Saver) { TabNavigation() }

@Composable
internal fun TabOverlays(container: AppContainer, tabs: TabNavigation) {
    val askRequested by AskRequests.requested.collectAsState()
    LaunchedEffect(askRequested) {
        askRequested?.let { from ->
            tabs.openAsk(from)
            AskRequests.consumed()
        }
    }
    BackHandler(enabled = tabs.showAsk && !tabs.showSearch && tabs.pages.isEmpty()) { tabs.closeAsk() }
    if (tabs.showAsk) {
        Surface(Modifier.fillMaxSize()) { AskScreen(container, onBack = tabs::closeAsk) }
    }
    BackHandler(enabled = tabs.showSearch && tabs.pages.isEmpty()) { tabs.closeSearch() }
    if (tabs.showSearch) {
        Surface(Modifier.fillMaxSize()) { SearchScreen(container, onBack = tabs::closeSearch) }
    }
    BackHandler(enabled = tabs.pages.isNotEmpty()) { tabs.back() }
    val pageStates = rememberSaveableStateHolder()
    tabs.pages.lastOrNull()?.let { page ->
        key(page) {
            Surface(Modifier.fillMaxSize()) {
                pageStates.SaveableStateProvider("${tabs.pages.size} ${page.label}") {
                    MusicPageScreen(container, page, onBack = tabs::back)
                }
            }
        }
    }
}

@Composable
internal fun rememberSearchOpener(tabs: TabNavigation, selected: TopLevelDestination): () -> Unit {
    val over by rememberUpdatedState(selected)
    return remember(tabs) { { tabs.searchOpener(over = over)() } }
}
