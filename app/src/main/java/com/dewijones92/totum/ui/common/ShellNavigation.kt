package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.cast.CastButton

internal val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

internal val LocalCastingTo = compositionLocalOf<String?> { null }

internal val LocalOpenMusicPage = staticCompositionLocalOf<((MusicPage) -> Unit)?> { null }

@Composable
fun SearchAction() {
    val open = LocalOpenSearch.current ?: return
    IconButton(onClick = open) {
        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.destination_search))
    }
}

@Composable
fun CastingAction() {
    val device = LocalCastingTo.current ?: return
    val label = stringResource(R.string.cast_strip_casting, device)
    CastButton(modifier = Modifier.size(CASTING_ACTION_SIZE).semantics { contentDescription = label })
}

private val CASTING_ACTION_SIZE = 48.dp
