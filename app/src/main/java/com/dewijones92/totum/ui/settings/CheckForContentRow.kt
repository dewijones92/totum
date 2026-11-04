package com.dewijones92.totum.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.notifications.NewContentCheck
import com.dewijones92.totum.notifications.NewContentNotifier
import kotlinx.coroutines.launch

/**
 * Runs the six-hourly new-content check by hand, and says what it found.
 *
 * Here because that job was the least observable thing in the app: it runs in the background,
 * every six hours, and until now said nothing at all — so "I never get notified about new
 * episodes" could not be investigated without waiting a quarter of a day per attempt. It calls
 * [NewContentCheck], the SAME object the worker calls, because a button running merely similar
 * code would prove the wrong thing.
 *
 * The undelivered case earns its own message: it almost always means notification permission was
 * never granted, which is a thing the user can simply fix once told.
 */
@Composable
internal fun CheckForContentRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !running) {
                running = true
                result = null
                scope.launch {
                    val outcome = NewContentCheck(
                        refresher = container.contentRefresher,
                        notify = { NewContentNotifier(context) { container.appPreferences.settings.value }.notify(it) },
                    ).run()
                    result = context.describe(outcome)
                    running = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (running) R.string.settings_check_content_running else R.string.settings_check_content,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = result ?: stringResource(R.string.settings_check_content_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One phrasing per outcome, so the screen and the log describe the same event. */
private fun Context.describe(outcome: NewContentCheck.Outcome): String = when (outcome) {
    is NewContentCheck.Outcome.NothingNew -> getString(R.string.settings_check_content_nothing)
    is NewContentCheck.Outcome.Notified ->
        resources.getQuantityString(R.plurals.settings_check_content_found, outcome.items, outcome.items)
    is NewContentCheck.Outcome.Undelivered ->
        resources.getQuantityString(
            R.plurals.settings_check_content_undelivered,
            outcome.items,
            outcome.items,
        )
    is NewContentCheck.Outcome.Failed ->
        getString(R.string.settings_check_content_failed, outcome.error.message ?: outcome.error.javaClass.simpleName)
}
