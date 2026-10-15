package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.AppPreferences

val LocalAppPreferences = staticCompositionLocalOf<AppPreferences?> { null }
const val INBOX_TOGGLE_TAG = "inboxToggle"

@Composable
fun InboxToggle(source: SourceId) {
    val prefs = LocalAppPreferences.current ?: return
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val on = settings.inboxAll || source.value in settings.inboxSources
    IconButton(
        onClick = { if (!settings.inboxAll) prefs.setInboxSource(source, !on) },
        enabled = !settings.inboxAll,
        modifier = Modifier.testTag(INBOX_TOGGLE_TAG),
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.MoveToInbox else Icons.Outlined.MoveToInbox,
            contentDescription = stringResource(
                when {
                    settings.inboxAll -> R.string.inbox_on_for_all
                    on -> R.string.inbox_stop
                    else -> R.string.inbox_start
                },
            ),
        )
    }
}
