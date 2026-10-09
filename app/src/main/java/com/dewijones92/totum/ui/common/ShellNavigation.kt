package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R

internal val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

internal val LocalOpenMusicPage = staticCompositionLocalOf<((MusicPage) -> Unit)?> { null }

@Composable
fun SearchAction() {
    val open = LocalOpenSearch.current ?: return
    IconButton(onClick = open) {
        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.destination_search))
    }
}
