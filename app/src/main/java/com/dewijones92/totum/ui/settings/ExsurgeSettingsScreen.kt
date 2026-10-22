package com.dewijones92.totum.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.exsurge.AndroidExsurgePorts
import com.dewijones92.totum.exsurge.BannerAction
import com.dewijones92.totum.exsurge.ExsurgeBannerService
import com.dewijones92.totum.exsurge.ExsurgeController
import com.dewijones92.totum.exsurge.ExsurgeEvent
import com.dewijones92.totum.exsurge.ExsurgeLiveUpdate
import com.dewijones92.totum.exsurge.ExsurgeSettings
import com.dewijones92.totum.exsurge.ExsurgeStatusCard
import com.dewijones92.totum.exsurge.ExsurgeView
import com.dewijones92.totum.exsurge.TakeoverActivity
import com.dewijones92.totum.exsurge.clockText
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.ui.common.BackHeader
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
fun ExsurgeSettingsScreen(exsurge: ExsurgeController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    KeepScreenAwake("exsurge page")
    val view by exsurge.view.collectAsStateWithLifecycle()
    val settings = view.settings
    val update: (ExsurgeSettings.() -> ExsurgeSettings) -> Unit = { change ->
        exsurge.updateSettings("settings") { it.change() }
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.exsurge_name), onBack)
            Text(
                stringResource(R.string.exsurge_motto),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val context = LocalContext.current
            ExsurgeStatusCard(view, onAction = { action -> onStatusAction(context, exsurge, action) })
            QuestCard(Icons.Outlined.PowerSettingsNew, stringResource(R.string.exsurge_name)) {
                SwitchRow(
                    label = stringResource(R.string.exsurge_settings_enabled),
                    summary = stringResource(R.string.exsurge_settings_enabled_summary),
                    checked = settings.enabled,
                    onCheckedChange = { on -> update { copy(enabled = on) } },
                    inset = 0.dp,
                )
            }
            StatsCard(view)
            QuestCard(Icons.Outlined.Schedule, stringResource(R.string.exsurge_settings_hours)) {
                HoursSection(settings, update)
            }
            QuestCard(Icons.Outlined.Timer, stringResource(R.string.exsurge_settings_timing)) {
                TIMING.forEach { spec -> Stepper(spec, settings, update) }
            }
            QuestCard(Icons.Outlined.Tune, stringResource(R.string.exsurge_settings_behaviour)) {
                BEHAVIOUR.forEach { spec ->
                    SwitchRow(
                        label = stringResource(spec.label),
                        summary = stringResource(spec.summary),
                        checked = spec.get(settings),
                        onCheckedChange = { v -> update { spec.set(this, v) } },
                        inset = 0.dp,
                    )
                }
                Stepper(VOLUME, settings, update)
            }
            QuestCard(Icons.Outlined.VerifiedUser, stringResource(R.string.exsurge_settings_permissions)) {
                PermissionsSection(settings)
            }
            QuestCard(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.exsurge_settings_destination)) {
                DestinationSection(settings, update)
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

private fun onStatusAction(context: Context, exsurge: ExsurgeController, action: BannerAction) {
    Diag.log(ExsurgeController.TAG, "dewidebug exsurge page action ${action.name}")
    when (action) {
        BannerAction.TURN_ON -> exsurge.updateSettings("page") { it.copy(enabled = true) }
        BannerAction.SUMMON_NOW -> exsurge.dispatch(ExsurgeEvent.SummonNow, "page")
        BannerAction.RESTART_CLOCK -> exsurge.dispatch(ExsurgeEvent.RestartClock, "page")
        BannerAction.GO -> context.startActivity(TakeoverActivity.intent(context, go = true))
        BannerAction.PAUSE_HOUR -> exsurge.dispatch(ExsurgeEvent.PauseHour, "page")
    }
}

private class StepperSpec(
    val label: Int,
    val unit: Int,
    val step: Int,
    val range: IntRange,
    val get: (ExsurgeSettings) -> Int,
    val set: ExsurgeSettings.(Int) -> ExsurgeSettings,
)

private class SwitchSpec(
    val label: Int,
    val summary: Int,
    val get: (ExsurgeSettings) -> Boolean,
    val set: ExsurgeSettings.(Boolean) -> ExsurgeSettings,
)

private val TIMING = listOf(
    StepperSpec(
        R.string.exsurge_settings_sitting,
        R.plurals.exsurge_unit_minutes,
        5,
        ExsurgeSettings.SITTING_RANGE,
        { it.sittingMinutes }
    ) {
        copy(sittingMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_break,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.BREAK_RANGE,
        { it.breakMinutes }
    ) {
        copy(breakMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_steps,
        R.plurals.exsurge_unit_steps,
        5,
        ExsurgeSettings.STEPS_TO_RISE_RANGE,
        { it.stepsToRise }
    ) {
        copy(stepsToRise = it)
    },
    StepperSpec(
        R.string.exsurge_settings_walk,
        R.plurals.exsurge_unit_steps,
        25,
        ExsurgeSettings.WALK_RESET_RANGE,
        { it.walkResetSteps }
    ) {
        copy(walkResetSteps = it)
    },
    StepperSpec(
        R.string.exsurge_settings_snooze,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.SNOOZE_RANGE,
        { it.snoozeMinutes }
    ) {
        copy(snoozeMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_call_interval,
        R.plurals.exsurge_unit_seconds,
        15,
        ExsurgeSettings.CALL_INTERVAL_RANGE,
        { it.callIntervalSeconds },
    ) {
        copy(callIntervalSeconds = it)
    },
    StepperSpec(
        R.string.exsurge_settings_rise_timeout,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.RISE_TIMEOUT_RANGE,
        { it.riseTimeoutMinutes },
    ) {
        copy(riseTimeoutMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_walk_window,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.WALK_WINDOW_RANGE,
        { it.walkWindowMinutes }
    ) {
        copy(walkWindowMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_mid_cue_minutes,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.MID_CUE_RANGE,
        { it.midCueMinutes }
    ) {
        copy(midCueMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_pause_minutes,
        R.plurals.exsurge_unit_minutes,
        15,
        ExsurgeSettings.PAUSE_RANGE,
        { it.pauseMinutes }
    ) {
        copy(pauseMinutes = it)
    },
    StepperSpec(
        R.string.exsurge_settings_flat_nudge_minutes,
        R.plurals.exsurge_unit_minutes,
        1,
        ExsurgeSettings.FLAT_NUDGE_RANGE,
        { it.flatNudgeMinutes },
    ) {
        copy(flatNudgeMinutes = it)
    },
)

private val VOLUME =
    StepperSpec(
        R.string.exsurge_settings_volume,
        R.plurals.exsurge_unit_percent,
        10,
        0..100,
        { it.voiceVolumePercent }
    ) {
        copy(voiceVolumePercent = it)
    }

private val BEHAVIOUR = listOf(
    SwitchSpec(R.string.exsurge_settings_escalate, R.string.exsurge_settings_escalate_summary, { it.escalate }) {
        copy(escalate = it)
    },
    SwitchSpec(R.string.exsurge_settings_mid_cue, R.string.exsurge_settings_mid_cue_summary, { it.midBreakCue }) {
        copy(midBreakCue = it)
    },
    SwitchSpec(R.string.exsurge_settings_quiet, R.string.exsurge_settings_quiet_summary, { it.quietOffice }) {
        copy(quietOffice = it)
    },
    SwitchSpec(
        R.string.exsurge_settings_pause_playback,
        R.string.exsurge_settings_pause_playback_summary,
        { it.pausePlayback },
    ) {
        copy(pausePlayback = it)
    },
    SwitchSpec(
        R.string.exsurge_settings_flat_nudge,
        R.string.exsurge_settings_flat_nudge_summary,
        { it.flatNudge },
    ) {
        copy(flatNudge = it)
    },
    SwitchSpec(
        R.string.exsurge_settings_over_apps,
        R.string.exsurge_settings_over_apps_summary,
        { it.takeoverOverApps },
    ) {
        copy(takeoverOverApps = it)
    },
)

@Composable
private fun StatsCard(view: ExsurgeView) {
    val stats = view.stats
    QuestCard(Icons.Outlined.EmojiEvents, stringResource(R.string.exsurge_settings_stats)) {
        val next = stats.rank.next
        Text(
            if (next == null) {
                stringResource(R.string.exsurge_stats_top_rank)
            } else {
                stringResource(R.string.exsurge_stats_next_rank, stats.laurelsToNextRank ?: 0, next.latin)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.exsurge_stats_streak, stats.streakDays),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(
                R.string.exsurge_stats_today,
                stats.today.completed,
                stats.today.skipped,
                stats.today.missed,
                stats.today.steps,
                stats.today.practised,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.exsurge_stats_week,
                stats.lastSevenDays.completed,
                stats.lastSevenDays.minutesStanding.toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HoursSection(settings: ExsurgeSettings, update: (ExsurgeSettings.() -> ExsurgeSettings) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in settings.activeDays
            FilterChip(
                selected = on,
                onClick = { update { copy(activeDays = if (on) activeDays - day else activeDays + day) } },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
    }
    TimeStepper(stringResource(R.string.exsurge_settings_from), settings.startMinuteOfDay) { v ->
        update {
            copy(startMinuteOfDay = v)
        }
    }
    TimeStepper(stringResource(R.string.exsurge_settings_until), settings.endMinuteOfDay) { v ->
        update {
            copy(endMinuteOfDay = v)
        }
    }
}

@Composable
private fun DestinationSection(settings: ExsurgeSettings, update: (ExsurgeSettings.() -> ExsurgeSettings) -> Unit) {
    var packageText by rememberSaveable { mutableStateOf(settings.destinationPackage) }
    var routeText by rememberSaveable { mutableStateOf(settings.destinationRoute) }
    OutlinedTextField(
        value = packageText,
        onValueChange = { v ->
            packageText = v
            update { copy(destinationPackage = v) }
        },
        label = { Text(stringResource(R.string.exsurge_settings_package)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    OutlinedTextField(
        value = routeText,
        onValueChange = { v ->
            routeText = v
            update { copy(destinationRoute = v) }
        },
        label = { Text(stringResource(R.string.exsurge_settings_route)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

private data class Check(val label: String, val granted: Boolean, val grant: (() -> Unit)?)

@Composable
private fun PermissionsSection(settings: ExsurgeSettings) {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        generation++
        onPauseOrDispose { }
    }
    val askActivity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { generation++ }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { generation++ }
    val checks = remember(generation, settings.destinationPackage) {
        checks(context, settings, askActivity::launch, askNotifications::launch)
    }
    checks.forEach { check ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Text(if (check.granted) "✅" else "❌", modifier = Modifier.padding(end = 12.dp))
            Text(check.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!check.granted && check.grant != null) {
                TextButton(onClick = check.grant) { Text(stringResource(R.string.exsurge_perm_grant)) }
            }
        }
    }
}

private fun checks(
    context: Context,
    settings: ExsurgeSettings,
    askActivity: (String) -> Unit,
    askNotifications: (String) -> Unit,
): List<Check> {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val alarms = context.getSystemService(AlarmManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    val packageUri = "package:${context.packageName}".toUri()
    fun open(action: String, withPackage: Boolean = true): () -> Unit = {
        context.startActivity(
            Intent(action).apply {
                if (withPackage) data = packageUri
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    val liveUpdate = ExsurgeLiveUpdate.allowed(context)?.let { allowed ->
        Check(
            context.getString(R.string.exsurge_perm_live_update),
            allowed,
            ExsurgeLiveUpdate.settingsIntent(context)?.let { intent -> { context.startActivity(intent) } },
        )
    }
    return listOfNotNull(
        Check(
            context.getString(R.string.exsurge_perm_notifications),
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        ) { askNotifications(Manifest.permission.POST_NOTIFICATIONS) },
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Check(
                context.getString(R.string.exsurge_perm_full_screen),
                notifications.canUseFullScreenIntent(),
                open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            )
        } else {
            null
        },
        liveUpdate,
        Check(context.getString(R.string.exsurge_perm_activity), ExsurgeBannerService.stepsPermitted(context)) {
            askActivity(Manifest.permission.ACTIVITY_RECOGNITION)
        },
        Check(
            context.getString(R.string.exsurge_perm_overlay),
            Settings.canDrawOverlays(context),
            open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        ),
        Check(
            context.getString(R.string.exsurge_perm_battery),
            power.isIgnoringBatteryOptimizations(context.packageName),
            open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, withPackage = false),
        ),
        Check(context.getString(R.string.exsurge_perm_exact), alarms.canScheduleExactAlarms(), null),
        Check(context.getString(R.string.exsurge_perm_sensor), AndroidExsurgePorts.hasStepSensor(context), null),
        Check(
            context.getString(R.string.exsurge_perm_destination, settings.destinationPackage),
            AndroidExsurgePorts.destinationInstalled(context, settings.destinationPackage),
            null,
        ),
    )
}

@Composable
private fun Stepper(
    spec: StepperSpec,
    settings: ExsurgeSettings,
    update: (ExsurgeSettings.() -> ExsurgeSettings) -> Unit,
) {
    val resources = LocalResources.current
    SnappingSlider(
        label = stringResource(spec.label),
        value = spec.get(settings),
        range = spec.range,
        step = spec.step,
        shown = { resources.getQuantityString(spec.unit, it, it) },
        tag = "exsurge-slider-${spec.label}",
    ) { chosen -> update { spec.set(this, chosen) } }
}

@Composable
private fun TimeStepper(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    SnappingSlider(
        label = label,
        value = minuteOfDay,
        range = 0..MINUTES_PER_DAY,
        step = HALF_HOUR,
        shown = ::clockText,
        tag = "exsurge-time-$label",
        onCommit = onChange,
    )
}

private const val HALF_HOUR = 30
private const val MINUTES_PER_DAY = 24 * ExsurgeSettings.MINUTES_PER_HOUR
