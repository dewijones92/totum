package com.dewijones92.totum.workapps

import android.content.Context
import android.content.pm.PackageManager
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.DailyAlarmNotifications
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
    is WorkAppsStatus.Paused -> getString(R.string.work_apps_line_paused, whenText(status.until, now, zone))
    is WorkAppsStatus.On -> getString(R.string.work_apps_line_on, whenText(status.until, now, zone))
    is WorkAppsStatus.Quiet ->
        status.until
            ?.let { getString(R.string.work_apps_line_quiet, namesOf(status.packages), whenText(it, now, zone)) }
            ?: getString(R.string.work_apps_line_quiet_no_days, namesOf(status.packages))
}

private fun Context.namesOf(packages: List<String>): String {
    if (packages.isEmpty()) return getString(R.string.work_apps_generic)
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
