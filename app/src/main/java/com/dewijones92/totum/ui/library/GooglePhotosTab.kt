package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.phone.google.PickerState
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PhoneMediaKind

@Composable
internal fun GooglePhotosTab(container: AppContainer, onOpenPhoto: (PhotoViewing) -> Unit) {
    val picks = container.phoneMedia.picks
    val state by picks.picker.state.collectAsStateWithLifecycle()
    var showName by rememberSaveable { mutableStateOf(PhoneMediaKind.PHOTO.name) }
    val showing = PhoneMediaKind.valueOf(showName)
    Column(Modifier.fillMaxSize()) {
        PickCard(
            state,
            onPick = picks::start,
            onCancel = picks::cancel,
            onSignOut = picks.picker::signOut,
            onDismiss = picks.picker::dismissResult
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PhoneMediaKind.PHOTO to R.string.google_show_photos,
                PhoneMediaKind.VIDEO to R.string.google_show_videos
            )
                .forEach { (kind, label) ->
                    FilterChip(
                        selected = showing == kind,
                        onClick = { showName = kind.name },
                        label = { Text(stringResource(label)) },
                    )
                }
        }
        val imports = container.phoneMedia.imports
        when (showing) {
            PhoneMediaKind.VIDEO -> MediaListTab(container, PhoneMediaKind.VIDEO, imports)
            else -> PhotoGridTab(container, imports, onOpenPhoto)
        }
    }
}

@Composable
private fun PickCard(
    state: PickerState,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag(GOOGLE_CARD_TAG)) {
        when (state) {
            PickerState.NeedsSetup -> {
                Text(stringResource(R.string.google_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.google_setup_body),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            PickerState.SignedOut, PickerState.Ready, PickerState.Cancelled -> PickPrompt(
                state == PickerState.Ready,
                onPick,
                onSignOut
            )
            PickerState.SigningIn -> Busy(stringResource(R.string.google_state_signing_in), onCancel)
            PickerState.CreatingSession -> Busy(stringResource(R.string.google_state_creating), onCancel)
            is PickerState.WaitingForPick -> Busy(stringResource(R.string.google_state_waiting), onCancel)
            is PickerState.ReadingPicks -> Busy(stringResource(R.string.google_state_reading, state.found), onCancel)
            is PickerState.Importing -> Busy(
                stringResource(R.string.google_state_importing, state.done + 1, state.total, state.current.orEmpty()),
                onCancel,
            )
            is PickerState.Finished -> Result(state, onDismiss)
            is PickerState.Failed -> Failure(stringResource(R.string.google_failed, state.reason), onDismiss)
            PickerState.Expired -> Failure(stringResource(R.string.google_expired), onDismiss)
        }
    }
}

@Composable
private fun PickPrompt(signedIn: Boolean, onPick: () -> Unit, onSignOut: () -> Unit) {
    Text(stringResource(R.string.google_pick_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.google_pick_body),
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodyMedium
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPick, modifier = Modifier.testTag(GOOGLE_PICK_TAG)) {
            Text(stringResource(R.string.google_pick_button))
        }
        if (signedIn) TextButton(onClick = onSignOut) { Text(stringResource(R.string.google_sign_out)) }
    }
    if (signedIn) Text(stringResource(R.string.google_signed_in), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Busy(text: String, onCancel: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onCancel) { Text(stringResource(R.string.google_cancel)) }
}

@Composable
private fun Result(done: PickerState.Finished, onDismiss: () -> Unit) {
    if (done.imported == 0 && done.alreadyHad == 0 && done.failed.isEmpty()) {
        Text(stringResource(R.string.google_done_nothing))
    }
    if (done.imported > 0) Text(pluralStringResource(R.plurals.google_done_imported, done.imported, done.imported))
    if (done.alreadyHad > 0) Text(pluralStringResource(R.plurals.google_done_already, done.alreadyHad, done.alreadyHad))
    if (done.failed.isNotEmpty()) {
        Text(
            pluralStringResource(R.plurals.google_done_failed, done.failed.size, done.failed.size),
            color = MaterialTheme.colorScheme.error
        )
        done.failed.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.google_dismiss)) }
}

@Composable
private fun Failure(text: String, onDismiss: () -> Unit) {
    Text(text, color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.google_dismiss)) }
}

const val GOOGLE_CARD_TAG = "google-photos-card"
const val GOOGLE_PICK_TAG = "google-photos-pick"
