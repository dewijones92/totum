package com.dewijones92.totum.dailyalarms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.common.rememberSnackbarOffers
import com.dewijones92.totum.ui.settings.NavRow
import com.dewijones92.totum.workapps.WorkAppsQuietActivity
import kotlinx.coroutines.delay

private const val NOW_REFRESH_MS = 30_000L

@Composable
fun DailyAlarmsScreen(
    alarms: DailyAlarmController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startWithNewAlarm: Boolean = false,
    newAlarmAsks: Int = 0,
) {
    KeepScreenAwake("dailyalarm settings")
    val context = LocalContext.current
    val view by alarms.view.collectAsStateWithLifecycle()
    val now by produceState(alarms.localNow(), alarms) {
        while (true) {
            delay(NOW_REFRESH_MS)
            value = alarms.localNow()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val delete = rememberDeleteWithUndo(alarms, snackbar)
    var adding by rememberSaveable { mutableStateOf(startWithNewAlarm) }
    LaunchedEffect(newAlarmAsks) { if (newAlarmAsks > 0) adding = true }
    if (adding) NewAlarmSheet(alarms, view.recentTitles) { adding = false }
    Surface(modifier = modifier.fillMaxSize()) {
        Box {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
                BackHeader(stringResource(R.string.alarms_tile), onBack) { NewAlarmButton { adding = true } }
                OneOffSection(view, alarms, now)
                Text(
                    stringResource(R.string.dailyalarm_name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                Text(
                    stringResource(R.string.dailyalarm_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                view.daily.forEach { alarm ->
                    val state = view.state(alarm.id)
                    val next = remember(alarm, state, now) { alarms.nextRing(alarm, state) }
                    AlarmCard(alarm, state, next, alarms, now) { delete(alarm) }
                }
                NavRow(stringResource(R.string.work_apps_open_row)) {
                    context.startActivity(WorkAppsQuietActivity.intent(context, "alarms page"))
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun rememberDeleteWithUndo(alarms: DailyAlarmController, snackbar: SnackbarHostState): (DailyAlarm) -> Unit {
    val resources = LocalResources.current
    return rememberRemoveWithUndo(
        offers = rememberSnackbarOffers(snackbar),
        undoLabel = resources.getString(R.string.dailyalarm_undo),
        tag = DailyAlarmController.TAG,
        duration = SnackbarDuration.Long,
        removedMessage = { removed: DailyAlarm -> resources.getString(R.string.dailyalarm_deleted, removed.label) },
        remove = { alarm: DailyAlarm -> alarms.remove(alarm.id, "page") },
        restore = { alarms.restore(it, "undo") },
    )
}
