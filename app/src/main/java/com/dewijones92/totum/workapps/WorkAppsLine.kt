package com.dewijones92.totum.workapps

import android.content.Context
import android.content.pm.PackageManager
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.DailyAlarmNotifications
import com.dewijones92.totum.dailyalarms.GroupState
import com.dewijones92.totum.dailyalarms.QuietKind
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.dailyalarms.WorkAppsStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private const val NAMES_SHOWN = 3

fun Context.appLabel(packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps no label for $packageName (not installed or not visible): $e")
    WorkAppsSettings.labelFor(packageName)
}

fun Context.workAppsLine(
    status: WorkAppsStatus,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (status) {
    WorkAppsStatus.NeedsAccess -> getString(R.string.work_apps_line_needs_access)
    is WorkAppsStatus.Groups -> status.states.joinToString(" · ") { groupLine(it, now, zone) }
}

fun Context.groupLine(state: GroupState, now: Instant, zone: ZoneId): String {
    val group = state.group
    val until = state.until?.let { whenText(it, now, zone) }
    return when {
        state.kind == QuietKind.PAUSED && until != null ->
            getString(R.string.work_apps_seg_paused, group.emoji, group.name, until)
        state.kind == QuietKind.ON && until != null ->
            getString(R.string.work_apps_seg_on, group.emoji, group.name, until)
        state.kind == QuietKind.QUIET && until != null ->
            getString(R.string.work_apps_seg_quiet, group.emoji, group.name, until)
        else -> getString(R.string.work_apps_seg_quiet_no_days, group.emoji, group.name)
    }
}

fun Context.namesOf(packages: List<String>): String {
    if (packages.isEmpty()) return getString(R.string.work_apps_apps_none)
    val shown = packages.take(NAMES_SHOWN).joinToString(" · ", transform = ::appLabel)
    val more = packages.size - NAMES_SHOWN
    return if (more > 0) getString(R.string.work_apps_names_more, shown, more) else shown
}

fun whenText(at: Instant, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val local = at.atZone(zone)
    val time = DailyAlarmNotifications.hhmm(local.toLocalTime())
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) {
        time
    } else {
        "$time ${local.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}"
    }
}
