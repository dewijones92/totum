package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.settings.DownloadPlace

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceChips(place: DownloadPlace, onChoose: (DownloadPlace) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DownloadPlace.entries.forEach { option ->
            FilterChip(
                selected = option == place,
                onClick = { onChoose(option) },
                label = {
                    val label = if (option == DownloadPlace.HOME) {
                        R.string.download_place_home
                    } else {
                        R.string.download_place_phone
                    }
                    Text(stringResource(label))
                },
                modifier = Modifier.testTag("$DOWNLOAD_PLACE_TAG-${option.stored}"),
            )
        }
    }
}

const val DOWNLOAD_PLACE_TAG = "download-place"
