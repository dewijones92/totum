package com.dewijones92.totum.ui.settings

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.StateSend
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.settings.AppPreferences
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val SEND_STATE_KEY = "send-state"
private const val BYTES_PER_KB = 1024

@Composable
internal fun SendReportsHomeRow(container: AppContainer, settings: AppPreferences.Settings) {
    SwitchRow(
        label = stringResource(R.string.settings_send_reports_home),
        summary = stringResource(R.string.settings_send_reports_home_summary),
        checked = settings.sendReportsToHome,
        onCheckedChange = container.appPreferences::setSendReportsToHome,
    )
}

@Composable
internal fun SendStateRow(container: AppContainer) {
    var sending by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    val sendingLabel = stringResource(R.string.settings_send_state_sending)
    val sentFormat = R.string.settings_send_state_sent
    val noSignIn = stringResource(R.string.settings_send_state_no_sign_in)
    val notGathered = stringResource(R.string.settings_send_state_not_gathered)
    val refused = stringResource(R.string.settings_send_state_refused)
    val testRun = stringResource(R.string.settings_send_state_test_run)
    val resources = androidx.compose.ui.platform.LocalResources.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !sending) {
                sending = true
                result = null
                Diag.log("diagnostics", "Send my state to Claude tapped")
                container.applicationScope.launch {
                    var outcome: StateSend = StateSend.NotGathered
                    container.operations.tracking(SEND_STATE_KEY, OperationKind.TASK, sendingLabel) {
                        outcome = suspendCoroutine { continuation -> container.sendStateToPi { continuation.resume(it) } }
                        when (val done = outcome) {
                            is StateSend.Sent -> OperationEnd.Done(1)
                            else -> OperationEnd.Failed(done.toString())
                        }
                    }
                    result = when (val done = outcome) {
                        is StateSend.Sent -> resources.getString(sentFormat, (done.bytes / BYTES_PER_KB).coerceAtLeast(1))
                        StateSend.NotSignedIn -> noSignIn
                        StateSend.NotGathered -> notGathered
                        StateSend.Refused -> refused
                        StateSend.TestRun -> testRun
                    }
                    sending = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (sending) sendingLabel else stringResource(R.string.settings_send_state),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = result ?: stringResource(R.string.settings_send_state_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
