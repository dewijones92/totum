package com.dewijones92.totum.ui.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MusicPage

@Composable
fun ArtistChoiceScreen(page: MusicPage.ArtistChoice, onBack: () -> Unit) {
    val open = LocalOpenMusicPage.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { BackHeader(title = stringResource(R.string.music_which_artist), onBack = onBack) }
        items(page.artists, key = { it.browseId ?: it.name }) { artist ->
            ArtistRow(
                name = artist.name,
                subtitle = null,
                artworkUrl = null,
                onOpen = {
                    Diag.log("music", "${page.label} -> ${artist.label}")
                    open?.invoke(artist)
                },
                modifier = Modifier.testTag("artist-choice-${artist.name}"),
                browseId = artist.browseId,
            )
        }
    }
}
