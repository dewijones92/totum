package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.pillar

@Composable
fun SourceChip(source: MediaSource, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(source.title) },
        leadingIcon = {
            SourceArtwork(
                url = source.artworkUrl,
                title = source.title,
                pillar = source.pillar,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}
