package com.dewijones92.totum.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.pillar

@Composable
fun SourceAvatar(source: MediaSource, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(AVATAR_CELL)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        SourceArtwork(
            url = source.artworkUrl,
            pillar = source.pillar,
            modifier = Modifier
                .size(AVATAR)
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = RING_ALPHA),
                    sourceArtworkShape(source.pillar)
                )
                .padding(3.dp),
        )
        Text(
            text = source.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            minLines = AVATAR_NAME_LINES,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
fun SourceAvatarStrip(sources: List<MediaSource>, onClick: (MediaSource) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(sources, key = { it.id.value }) { source -> SourceAvatar(source, onClick = { onClick(source) }) }
    }
}

private val AVATAR = 60.dp
private val AVATAR_CELL = 76.dp
private const val RING_ALPHA = 0.55f
private const val AVATAR_NAME_LINES = 2
