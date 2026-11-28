package com.dewijones92.totum.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.dewijones92.totum.pins.titleThrough
import com.dewijones92.totum.treat.TreatStatus
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.treat.TreatSheet
import com.dewijones92.totum.ui.treat.TreatToasts
import com.dewijones92.totum.ui.treat.treatStatusText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TreatTile(container: AppContainer) {
    val treat = container.treat ?: return
    val choice by treat.choice.collectAsStateWithLifecycle()
    val status by treat.status.collectAsStateWithLifecycle()
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    TreatToasts(status)
    val scheme = MaterialTheme.colorScheme
    val pinTitle = pins.firstOrNull { it.key == choice.pinKey }?.titleThrough(LocalItemShield.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.secondaryContainer)
            .testTag(TREAT_TILE_TAG),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = {
                        Diag.log("treat", "dewidebug the treat tile was tapped while ${status::class.simpleName}")
                        if (status is TreatStatus.NotSetUp) sheet = true else treat.tap("the Library tile")
                    },
                    onLongClick = {
                        Diag.log("treat", "dewidebug the treat tile was long-pressed: opening its settings")
                        sheet = true
                    },
                )
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.treat_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSecondaryContainer,
            )
            Text(
                text = treatStatusText(status, choice, pinTitle, LocalNow.current.toEpochMilli()),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = { sheet = true }) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.treat_set_up_description),
                tint = scheme.onSecondaryContainer,
            )
        }
    }
    if (sheet) TreatSheet(container, treat, onDismiss = { sheet = false })
}

const val TREAT_TILE_TAG = "library-treat-tile"
