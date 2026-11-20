package com.dewijones92.totum.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

@Composable
internal fun PhoneMediaTile(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.tertiaryContainer)
            .clickable {
                Diag.log("phone-media", "the On this phone tile was tapped")
                onOpen()
            }
            .padding(16.dp)
            .testTag(PHONE_MEDIA_TILE_TAG),
    ) {
        Text(
            text = stringResource(R.string.phone_media_title),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onTertiaryContainer,
        )
        Text(
            text = stringResource(R.string.phone_media_tile_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

const val PHONE_MEDIA_TILE_TAG = "library-phone-media-tile"
