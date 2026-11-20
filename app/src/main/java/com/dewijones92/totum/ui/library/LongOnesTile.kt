package com.dewijones92.totum.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer

@Composable
internal fun LongOnesTile(container: AppContainer, onOpen: () -> Unit) {
    val longOnes = container.longOnes ?: return
    val state by longOnes.state.collectAsStateWithLifecycle()
    val ready = state.items?.size
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.tertiaryContainer)
            .clickable {
                Diag.log("longones", "dewidebug the Long ones tile was tapped; ${ready ?: "none"} ready in memory")
                onOpen()
            }
            .padding(16.dp)
            .testTag(LONG_ONES_TILE_TAG),
    ) {
        Text(
            text = stringResource(R.string.longones_title),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onTertiaryContainer,
        )
        Text(
            text = stringResource(R.string.longones_tile_blurb),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (ready != null && ready > 0) {
            Text(
                text = pluralStringResource(R.plurals.longones_tile_ready, ready, ready),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp).testTag(LONG_ONES_TILE_READY_TAG),
            )
        }
    }
}

const val LONG_ONES_TILE_TAG = "library-long-ones-tile"
const val LONG_ONES_TILE_READY_TAG = "library-long-ones-tile-ready"
