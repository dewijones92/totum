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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.ui.trip.tripLines

@Composable
internal fun TripTile(container: AppContainer, onOpen: () -> Unit) {
    val trip = container.tripMode ?: return
    val status by trip.status.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val lines = tripLines(status)
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.secondaryContainer)
            .clickable {
                Diag.log("trip", "the trip tile was tapped; status ${status::class.simpleName}")
                onOpen()
            }
            .padding(16.dp)
            .testTag(TRIP_TILE_TAG),
    ) {
        Text(
            text = stringResource(R.string.trip_title),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSecondaryContainer,
        )
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

const val TRIP_TILE_TAG = "library-trip-tile"
