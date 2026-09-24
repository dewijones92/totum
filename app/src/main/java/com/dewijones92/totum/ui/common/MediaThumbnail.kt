package com.dewijones92.totum.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaKind

/**
 * The one thumbnail/artwork image in the app — used identically for podcast
 * episodes, videos, channels and search hits. Give it whatever [url] the item
 * carries ([com.dewijones92.totum.domain.MediaItem.thumbnailUrl],
 * `SearchHit.artworkUrl`, `PlaybackState.artworkUrl`); a missing or
 * still-loading image shows a neutral placeholder glyph, so a null [url] is a
 * perfectly valid, tidy state rather than a blank hole.
 *
 * The image crops to fill its box ([ContentScale.Crop]) so mixed aspect ratios
 * (square podcast art, 16:9 video stills) sit uniformly in a list.
 *
 * [durationLabel] rides in the bottom-right corner, where every video app puts it and
 * where nothing can push it out: as part of the subtitle line it was the first thing an
 * ellipsis ate, so the one number you scan a list for was the one most often missing.
 */
@Composable
fun MediaThumbnail(
    url: HttpUrl?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    // 12dp rather than 8: at the larger artwork size 8dp reads as an almost-square with the corners
    // knocked off, where 12 reads as deliberate. Matches the cards and sheets elsewhere.
    shape: Shape = RoundedCornerShape(12.dp),
    durationLabel: String? = null,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // Sits behind the image, so it shows through while loading and stays
        // visible if the url is null or the fetch fails.
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(PLACEHOLDER_GLYPH_FRACTION),
        )
        if (url != null) {
            AsyncImage(
                model = url.value,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        durationLabel?.let { DurationChip(it, Modifier.align(Alignment.BottomEnd)) }
    }
}

/** Black-on-white-ish so it stays readable over any still, as YouTube's own does. */
@Composable
private fun DurationChip(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = CHIP_SCRIM))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private const val PLACEHOLDER_GLYPH_FRACTION = 0.4f
private const val CHIP_SCRIM = 0.72f

@Composable
fun SourceArtwork(url: HttpUrl?, pillar: MediaKind, modifier: Modifier = Modifier) {
    MediaThumbnail(
        url = url,
        contentDescription = null,
        modifier = modifier,
        shape = when (pillar) {
            MediaKind.VIDEO -> CircleShape
            MediaKind.PODCAST -> RoundedCornerShape(8.dp)
        },
    )
}
