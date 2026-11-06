package com.dewijones92.totum.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.SwitchRow

class RecorderActivity : ComponentActivity() {
    private val store by lazy { RecorderChoiceStore(this) }
    private var asked = RecorderChoices()
    private var chosen = RecorderChoices()
    private var overlayAllowed by mutableStateOf(false)
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissions(it) }
    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onConsent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val from = intent?.getStringExtra(EXTRA_FROM) ?: FROM_SHORTCUT
        Diag.log(TAG, "dewidebug recorder options opened from the $from recording=${RecorderState.recording}")
        if (!RecorderState.recording) SystemTaps(this).restore()
        setContent {
            TotumTheme {
                val status by RecorderState.status.collectAsState()
                if (status is RecorderStatus.Recording) {
                    RunningDialog(onStop = ::stopRecording, onDismiss = ::finish)
                } else {
                    var choices by remember { mutableStateOf(store.read()) }
                    OptionsDialog(
                        choices = choices,
                        overlayAllowed = overlayAllowed,
                        tapsEverywhere = SystemTaps(this).permitted(),
                        onChange = { choices = it },
                        onAllowOverlay = ::openOverlaySettings,
                        onStart = { start(choices) },
                        onDismiss = {
                            Diag.log(TAG, "dewidebug recorder options dismissed without starting")
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
    }

    private fun stopRecording() {
        RecorderService.stop(this, "the Stop button in the recorder dialog")
        finish()
    }

    private fun openOverlaySettings() {
        Diag.log(TAG, "dewidebug recorder opening the display-over-other-apps settings")
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
        )
    }

    private fun start(choices: RecorderChoices) {
        store.write(choices)
        asked = choices
        val wanted = buildList {
            if (choices.mic) add(Manifest.permission.RECORD_AUDIO)
            if (choices.face) add(Manifest.permission.CAMERA)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::granted)
        Diag.log(TAG, "dewidebug recorder start asked=$choices permissionsToAsk=$wanted")
        if (wanted.isEmpty()) onPermissions(emptyMap()) else permissions.launch(wanted.toTypedArray())
    }

    private fun onPermissions(result: Map<String, Boolean>) {
        Diag.log(TAG, "dewidebug recorder permission answers=$result")
        var effective = asked
        if (effective.mic && !granted(Manifest.permission.RECORD_AUDIO)) {
            effective = effective.copy(mic = false)
            say(R.string.recorder_mic_refused)
        }
        if (effective.face && !granted(Manifest.permission.CAMERA)) {
            effective = effective.copy(face = false)
            say(R.string.recorder_camera_refused)
        } else if (effective.face && !Settings.canDrawOverlays(this)) {
            effective = effective.copy(face = false)
            say(R.string.recorder_overlay_missing)
        }
        chosen = effective
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                if (effective.face) {
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                } else {
                    MediaProjectionConfig.createConfigForUserChoice()
                },
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        Diag.log(TAG, "dewidebug recorder asking Android for screen capture consent effective=$effective")
        consent.launch(intent)
    }

    private fun onConsent(result: ActivityResult) {
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            Diag.log(TAG, "dewidebug recorder screen capture consent given; starting the recorder service")
            RecorderService.start(this, result.resultCode, data, chosen)
        } else {
            Diag.log(TAG, "dewidebug recorder screen capture consent refused resultCode=${result.resultCode}")
            say(R.string.recorder_consent_refused)
        }
        finish()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun say(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "recorder"
        private const val EXTRA_FROM = "totum.recorder.from"
        private const val FROM_SHORTCUT = "app-icon shortcut"
        const val ACTION_RECORD = "com.dewijones92.totum.RECORD_SCREEN"

        fun intent(context: Context, from: String = FROM_SHORTCUT): Intent =
            Intent(context, RecorderActivity::class.java)
                .setAction(ACTION_RECORD)
                .putExtra(EXTRA_FROM, from)
    }
}

@Composable
private fun RunningDialog(onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recorder_dialog_title)) },
        text = { Text(stringResource(R.string.recorder_recording_now)) },
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.recorder_stop)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.recorder_cancel)) } },
    )
}

@Composable
@Suppress("LongParameterList")
private fun OptionsDialog(
    choices: RecorderChoices,
    overlayAllowed: Boolean,
    tapsEverywhere: Boolean,
    onChange: (RecorderChoices) -> Unit,
    onAllowOverlay: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recorder_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.recorder_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.recorder_voice_title),
                    summary = stringResource(R.string.recorder_voice_note),
                    checked = choices.mic,
                    onCheckedChange = { onChange(choices.copy(mic = it)) },
                    inset = 0.dp,
                )
                SwitchRow(
                    label = stringResource(R.string.recorder_face_title),
                    summary = stringResource(R.string.recorder_face_note),
                    checked = choices.face,
                    onCheckedChange = { onChange(choices.copy(face = it)) },
                    inset = 0.dp,
                )
                if (choices.face && !overlayAllowed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.recorder_face_needs_overlay),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAllowOverlay) { Text(stringResource(R.string.recorder_face_allow)) }
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.recorder_taps_title),
                    summary = stringResource(
                        if (tapsEverywhere) R.string.recorder_taps_system else R.string.recorder_taps_in_totum,
                    ),
                    checked = choices.taps,
                    onCheckedChange = { onChange(choices.copy(taps = it)) },
                    inset = 0.dp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text(stringResource(R.string.recorder_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.recorder_cancel)) } },
    )
}
