package com.dewijones92.totum.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.R

/**
 * Shows exactly which build is running (version + git SHA). For a
 * rolling-release app tracked in Obtainium, this makes the installed commit
 * unambiguous.
 */
@Composable
fun BuildInfoFooter(modifier: Modifier = Modifier) {
    val version = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}"
    val copy = rememberCopy(version, stringResource(R.string.version_noun))
    Text(
        text = version,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = copy)
            .padding(vertical = 12.dp),
    )
}
