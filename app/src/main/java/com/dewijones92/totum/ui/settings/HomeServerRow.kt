package com.dewijones92.totum.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Points the app at the home server, and signs in to it.
 *
 * Sign-in opens a real browser rather than anything embedded, and that is forced rather than
 * chosen: Google refuses OAuth inside a WebView, and a Custom Tab's cookies live in Chrome where
 * the app cannot read them. So the server's gated page hands the token back by deep link
 * (`totum://auth`), and the app stores that.
 *
 * The row states whether it is signed in, because "the home server section is empty" has two very
 * different causes — never signed in, or signed in and simply nothing found.
 */
@Composable
internal fun HomeServerRow(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_home_server), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    settings.homeServerBase.isBlank() -> stringResource(R.string.settings_home_server_none)
                    settings.homeServerToken.isBlank() ->
                        stringResource(R.string.settings_home_server_signed_out, settings.homeServerBase)
                    // A token without a key reaches the server and is refused by Prowlarr, so
                    // "signed in" would be true and useless. Both or neither.
                    settings.prowlarrApiKey.isBlank() ->
                        stringResource(R.string.settings_home_server_no_key, settings.homeServerBase)
                    else -> stringResource(R.string.settings_home_server_ready, settings.homeServerBase)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editing) {
        HomeServerDialog(
            container = container,
            initialBase = settings.homeServerBase,
            initialKey = settings.prowlarrApiKey,
            signedIn = settings.homeServerToken.isNotBlank(),
            onDone = { editing = false },
        )
    }
}

/**
 * Address and key together, then straight into the browser.
 *
 * Both are asked for at once because neither works alone, and sign-in follows immediately: with
 * nothing to sign in to, saving an address leaves the feature looking broken rather than
 * half-configured.
 */
@Composable
private fun HomeServerDialog(
    container: AppContainer,
    initialBase: String,
    initialKey: String,
    signedIn: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var base by remember { mutableStateOf(initialBase) }
    var key by remember { mutableStateOf(initialKey) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.settings_home_server)) },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text(stringResource(R.string.settings_home_server_domain)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.settings_home_server_key)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (signedIn) {
                    TextButton(
                        onClick = {
                            container.applicationScope.launch {
                                container.homeTorrentServer?.signOut()
                                container.appPreferences.setHomeServerToken("")
                            }
                            onDone()
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.settings_home_server_sign_out)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A BLANK key never overwrites a stored one. The key arrives automatically on
                // the sign-in deep link now, so this box is normally empty — and saving it
                // blank would delete the very thing that had just been fetched, turning
                // "sign in again" into "break it again".
                container.appPreferences.setHomeServer(base, key.ifBlank { initialKey })
                onDone()
                if (base.isNotBlank()) {
                    val device = Uri.encode(thisDevice())
                    Diag.log("auth", "signing in to the home server as \"${thisDevice()}\"")
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://totumauth.$base/?device=$device".toUri()))
                }
            }) { Text(stringResource(R.string.settings_home_server_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.podcast_refresh_failed_dismiss)) }
        },
    )
}

private fun thisDevice(): String =
    "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
