package com.dewijones92.totum.ui.common

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.ExploreMode

internal val LocalExploreMode = staticCompositionLocalOf<ExploreMode?> { null }

@Composable
fun ExploreToggle(from: String, modifier: Modifier = Modifier) {
    val explore = LocalExploreMode.current ?: return
    val on by explore.on.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saidOn = stringResource(R.string.explore_on)
    val saidOff = stringResource(R.string.explore_off)
    IconToggleButton(
        checked = on,
        onCheckedChange = { enabled ->
            explore.set(enabled, from)
            Toast.makeText(context, if (enabled) saidOn else saidOff, Toast.LENGTH_SHORT).show()
        },
        modifier = modifier.testTag("$EXPLORE_TOGGLE_TAG:$from"),
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.Explore else Icons.Outlined.Explore,
            contentDescription = stringResource(R.string.explore_toggle),
            tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

@Composable
fun ExploreMenuItem(from: String, close: () -> Unit) {
    val explore = LocalExploreMode.current ?: return
    val on by explore.on.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saidOn = stringResource(R.string.explore_on)
    val saidOff = stringResource(R.string.explore_off)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.explore_toggle)) },
        leadingIcon = {
            Icon(
                imageVector = if (on) Icons.Filled.Explore else Icons.Outlined.Explore,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        },
        onClick = {
            close()
            explore.set(!on, from)
            Toast.makeText(context, if (on) saidOff else saidOn, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.testTag("$EXPLORE_TOGGLE_TAG:$from"),
    )
}

const val EXPLORE_TOGGLE_TAG = "explore-toggle"
