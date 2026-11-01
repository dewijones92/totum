package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.domain.MediaKind

/** The icon that marks each pillar — shared so every surface uses the same glyph. */
fun pillarIcon(kind: MediaKind, music: Boolean = false): ImageVector =
    if (music) Shown.MUSIC.icon else if (kind == MediaKind.VIDEO) Shown.VIDEO.icon else Shown.PODCAST.icon

/**
 * Little icon + label saying which pillar the current item is — a YouTube video
 * or a podcast. One badge, used by the full player and (icon-only) the mini
 * player, so the distinction reads the same everywhere.
 */
@Composable
fun PillarBadge(shown: Shown, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            imageVector = shown.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(shown.label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
