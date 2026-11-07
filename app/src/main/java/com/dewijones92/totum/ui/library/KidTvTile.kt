package com.dewijones92.totum.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.kidlock.KidTvStatus
import com.dewijones92.totum.pins.titleThrough
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.ui.kidtv.KidTvToasts
import com.dewijones92.totum.ui.kidtv.kidTvStatusText

@Composable
internal fun KidTvTile(container: AppContainer, onSetUp: () -> Unit) {
    val kidTv = container.kidTv ?: return
    val choice by kidTv.choice.collectAsStateWithLifecycle()
    val status by kidTv.status.collectAsStateWithLifecycle()
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    KidTvToasts(status)
    val scheme = MaterialTheme.colorScheme
    val pinTitle = pins.firstOrNull { it.key == choice.pinKey }?.titleThrough(LocalItemShield.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.tertiaryContainer)
            .testTag(KID_TV_TILE_TAG),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    Diag.log("kidlock", "the Kid TV tile was tapped while ${status::class.simpleName}")
                    if (status is KidTvStatus.NotSetUp) onSetUp() else kidTv.tap("the Library tile")
                }
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.kidtv_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onTertiaryContainer,
            )
            Text(
                text = kidTvStatusText(status, pinTitle, choice.tvName),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = onSetUp) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.kidtv_set_up_description),
                tint = scheme.onTertiaryContainer,
            )
        }
    }
}

const val KID_TV_TILE_TAG = "library-kid-tv-tile"
