package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.jobs.MorningWorker
import com.dewijones92.totum.jobs.agoText
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.ui.common.LocalNow
import kotlinx.coroutines.launch

internal const val MORNING_DIGEST_TAG = "morningDigest"
internal const val MORNING_FOR_YOU_TAG = "morningForYou"
internal const val MORNING_RUN_TAG = "morningRunNow"
private const val MORNING_KEY = "morning-job"

@Composable
internal fun MorningSettings(container: AppContainer) {
    val store = container.aiFeatures.morningStore
    val state by store.state.collectAsStateWithLifecycle()
    val last by container.lastRuns.observe(MorningWorker.JOB_NAME).collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val now = LocalNow.current.toEpochMilli()
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.morning_op_label)
    val lastLine = when {
        last == null -> stringResource(R.string.settings_morning_never)
        last?.succeeded == true ->
            stringResource(R.string.settings_morning_last_ok, context.agoText(last!!.ranAtMs, now), last!!.detail)
        else -> stringResource(R.string.settings_morning_last_failed, last?.detail.orEmpty())
    }
    SectionTitle(stringResource(R.string.settings_morning_section))
    AnchoredRow(SettingsRow.MORNING_DIGEST) {
        SwitchRow(
            label = stringResource(R.string.settings_morning_digest),
            summary = stringResource(R.string.settings_morning_digest_summary),
            checked = state.digest,
            onCheckedChange = store::setDigest,
        )
    }
    AnchoredRow(SettingsRow.MORNING_FOR_YOU) {
        SwitchRow(
            label = stringResource(R.string.settings_morning_for_you),
            summary = stringResource(R.string.settings_morning_for_you_summary) + "\n" + lastLine,
            checked = state.forYou,
            onCheckedChange = store::setForYou,
        )
    }
    Button(
        onClick = {
            if (container.operations.isRunning(MORNING_KEY)) return@Button
            scope.launch {
                container.operations.tracking(MORNING_KEY, OperationKind.TASK, label) {
                    val job = container.aiFeatures.morningJob ?: return@tracking OperationEnd.Failed("no morning job")
                    val result = job.runAndTell("settings")
                    container.lastRuns.succeeded(MorningWorker.JOB_NAME, job.detail(result))
                    OperationEnd.Done(result.picks ?: 0, note = result.digestLines?.firstOrNull())
                }
            }
        },
        enabled = state.digest || state.forYou,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(MORNING_RUN_TAG),
    ) { Text(stringResource(R.string.settings_morning_run_now)) }
}
