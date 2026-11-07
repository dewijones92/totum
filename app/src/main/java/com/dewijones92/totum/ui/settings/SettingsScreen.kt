package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.bedtime.BedtimeScreen
import com.dewijones92.totum.dailyalarms.DailyAlarmsScreen
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.diagnosticsNote
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.ListThenDetail
import com.dewijones92.totum.ui.importexport.ImportExportScreen
import com.dewijones92.totum.ui.kidtv.KidTvScreen
import com.dewijones92.totum.workapps.WorkAppsQuietScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selectable video-quality cap for the per-network preference. */
private data class QualityOption(val label: String, val height: Int)

private val QUALITY_OPTIONS = listOf(
    QualityOption("Best", AppPreferences.UNCAPPED),
    QualityOption("2160p", 2160),
    QualityOption("1440p", 1440),
    QualityOption("1080p", 1080),
    QualityOption("720p", 720),
    QualityOption("480p", 480),
    QualityOption("360p", 360),
    QualityOption("240p", 240),
)

private fun labelFor(height: Int): String =
    QUALITY_OPTIONS.firstOrNull { it.height == height }?.label ?: "${height}p"

/**
 * App settings. Currently the per-network default video-quality caps (the
 * quality auto-picked when a video starts, so mobile data is saved). Shown as a
 * full-screen layer over the Account tab.
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val prefs = container.appPreferences
    val settings by prefs.settings.collectAsStateWithLifecycle()
    var subPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val subPage = subPageName?.let(SettingsSubPage::valueOf)
    val focus = remember { SettingsFocus() }
    val open: (SettingsSubPage?) -> Unit = { subPageName = it?.name }
    val close = { open(null) }
    ListThenDetail(subPage, listKey = "settings", onBack = close, list = {
        CompositionLocalProvider(LocalSettingsFocus provides focus) {
            Surface(modifier = modifier.fillMaxSize()) {
                // Scrolls: the screen already ran past the bottom of a phone once the
                // SponsorBlock categories were added, and everything below was simply unreachable.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    BackHeader(stringResource(R.string.settings), onBack)
                    SettingsSearchBox(focus, open) { MainSettings(container, settings, open) }
                }
            }
        }
    }) { open ->
        when (open) {
            SettingsSubPage.IMPORT_EXPORT ->
                ImportExportScreen(container, onBack = close, modifier = modifier)
            SettingsSubPage.EXSURGE ->
                ExsurgeSettingsScreen(container.exsurge, onBack = close, modifier = modifier)
            SettingsSubPage.DAILY_ALARMS ->
                DailyAlarmsScreen(container.dailyAlarms, onBack = close, modifier = modifier)
            SettingsSubPage.WORK_APPS ->
                WorkAppsQuietScreen(container.workApps, onBack = close, modifier = modifier)
            SettingsSubPage.BEDTIME ->
                BedtimeScreen(container.bedtime, onBack = close, modifier = modifier)
            SettingsSubPage.DIAGNOSTICS -> DiagnosticsScreen(onBack = close, modifier = modifier)
            SettingsSubPage.NOT_FOR_ME -> NotForMeScreen(container.notForMe, onBack = close, modifier = modifier)
            SettingsSubPage.KID_TV -> KidTvScreen(container, onBack = close, modifier = modifier)
        }
    }
}

@Composable
private fun MainSettings(
    container: AppContainer,
    settings: AppPreferences.Settings,
    open: (SettingsSubPage?) -> Unit,
) {
    val prefs = container.appPreferences
    Column {
        QualitySection(settings, prefs, container)
        FeedAndQueueSettings(settings, prefs, container.lastRuns)
        NewUploadsSettings(container, settings)
        SkipSilenceSection(container, settings.silenceMode, prefs::setSilenceMode)
        SpeechBoostSection(container, settings.speechGate, prefs::setSpeechGate)
        DownloadSettings(settings, prefs)
        MorningSettings(container)
        SideQuestRows(open)
        Text(
            text = stringResource(R.string.settings_subscriptions_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        AnchoredRow(SettingsRow.YOUTUBE_ALERTS) {
            SwitchRow(
                label = stringResource(R.string.settings_youtube_alerts),
                summary = stringResource(R.string.settings_youtube_alerts_summary),
                checked = settings.youTubeUploadAlerts,
                onCheckedChange = prefs::setYouTubeUploadAlerts,
            )
        }
        AnchoredRow(SettingsRow.PODCAST_ALERTS) {
            SwitchRow(
                label = stringResource(R.string.settings_podcast_alerts),
                summary = stringResource(R.string.settings_podcast_alerts_summary),
                checked = settings.podcastEpisodeAlerts,
                onCheckedChange = prefs::setPodcastEpisodeAlerts,
            )
        }
        AnchoredRow(SettingsRow.IMPORT_EXPORT) {
            NavRow(
                label = stringResource(R.string.settings_import_export),
                onClick = { open(SettingsSubPage.IMPORT_EXPORT) },
            )
        }
        SectionTitle(stringResource(R.string.settings_diagnostics_section))
        SkipCategorySection(settings.skipCategories, prefs::setSkipCategories)

        AnchoredRow(SettingsRow.VIEW_DIAGNOSTICS) {
            ViewDiagnosticsRow(onOpen = { open(SettingsSubPage.DIAGNOSTICS) })
        }
        AnchoredRow(SettingsRow.HOME_SERVER) { HomeServerRow(container) }
        AnchoredRow(SettingsRow.CHECK_CONTENT) { CheckForContentRow(container) }
        AnchoredRow(SettingsRow.SEND_DIAGNOSTICS) { DiagnosticsRow(container) }
        SectionTitle(stringResource(R.string.drive_backup_section))
        AnchoredRow(
            SettingsRow.DRIVE_BACKUP
        ) { DriveBackupRow(container.driveBackup, container.operations, container.lastRuns) }
        SponsorRow()
    }
}

@Composable
private fun SideQuestRows(open: (SettingsSubPage?) -> Unit) {
    AnchoredRow(SettingsRow.EXSURGE) {
        NavRow(
            label = stringResource(R.string.exsurge_settings_row),
            onClick = { open(SettingsSubPage.EXSURGE) }
        )
    }
    AnchoredRow(SettingsRow.DAILY_ALARMS) {
        NavRow(
            label = stringResource(R.string.dailyalarm_settings_row),
            onClick = { open(SettingsSubPage.DAILY_ALARMS) }
        )
    }
    AnchoredRow(SettingsRow.WORK_APPS) {
        NavRow(
            label = stringResource(R.string.work_apps_name),
            onClick = { open(SettingsSubPage.WORK_APPS) }
        )
    }
    AnchoredRow(SettingsRow.BEDTIME) {
        NavRow(
            label = stringResource(R.string.bedtime_name),
            onClick = { open(SettingsSubPage.BEDTIME) }
        )
    }
    AnchoredRow(SettingsRow.NOT_FOR_ME) {
        NavRow(
            label = stringResource(R.string.not_for_me_settings_row),
            onClick = { open(SettingsSubPage.NOT_FOR_ME) }
        )
    }
    AnchoredRow(SettingsRow.KID_TV) {
        NavRow(label = stringResource(R.string.kidtv_title), onClick = { open(SettingsSubPage.KID_TV) })
    }
}

/** Automatic-download preferences: whether, and on which networks. */
@Composable
private fun DownloadSettings(settings: AppPreferences.Settings, prefs: AppPreferences) {
    SectionTitle(stringResource(R.string.settings_downloads_section))
    AnchoredRow(SettingsRow.AUTO_DOWNLOAD) {
        SwitchRow(
            label = stringResource(R.string.settings_auto_download),
            summary = stringResource(R.string.settings_auto_download_summary),
            checked = settings.autoDownloadQueue,
            onCheckedChange = prefs::setAutoDownloadQueue,
        )
    }
    AnchoredRow(SettingsRow.AUTO_DOWNLOAD_WIFI) {
        SwitchRow(
            label = stringResource(R.string.settings_auto_download_wifi),
            summary = stringResource(R.string.settings_auto_download_wifi_summary),
            checked = settings.autoDownloadWifiOnly,
            onCheckedChange = prefs::setAutoDownloadWifiOnly,
            enabled = settings.autoDownloadQueue,
        )
    }
    AnchoredRow(SettingsRow.COPY_HOME) {
        SwitchRow(
            label = stringResource(R.string.settings_copy_home),
            summary = stringResource(R.string.settings_copy_home_summary),
            checked = settings.copyHomeOnWifi,
            onCheckedChange = prefs::setCopyHomeOnWifi,
        )
    }
}

/**
 * Sends the current state and event trail to the crash sink. Deliberately available
 * without a crash: most defects are "it behaved wrongly", not "it died".
 */
/** The per-network quality caps: the quality auto-picked when a video starts. */
@Composable
private fun QualitySection(settings: AppPreferences.Settings, prefs: AppPreferences, container: AppContainer) {
    Text(
        text = stringResource(R.string.settings_quality_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    AnchoredRow(SettingsRow.QUALITY_WIFI) {
        QualityRow(
            label = stringResource(R.string.settings_quality_wifi),
            current = settings.wifiMaxHeight,
            onSelect = prefs::setWifiMaxHeight,
        )
    }
    AnchoredRow(SettingsRow.QUALITY_CELLULAR) {
        QualityRow(
            label = stringResource(R.string.settings_quality_cellular),
            current = settings.cellularMaxHeight,
            onSelect = prefs::setCellularMaxHeight,
        )
    }
    AnchoredRow(SettingsRow.DATA_LIMIT) { DataLimitRow(container.mobileData, settings, prefs) }
}

/** Opens the on-device read of the same data the "send" button transmits. */
@Composable
private fun ViewDiagnosticsRow(onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.diagnostics_view),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.diagnostics_view_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val diagnosticsSaving = MutableStateFlow(false)

@Composable
private fun DiagnosticsRow(container: AppContainer) {
    var sent by rememberSaveable { mutableStateOf(false) }
    val saving by diagnosticsSaving.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(false) }
    val fallback = stringResource(R.string.settings_diagnostics_note)
    val appSettings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val toGitHub = !appSettings.signedInToHome()

    if (asking) {
        ReportDialog(
            container = container,
            onDismiss = { asking = false },
            onSendToPi = { typed, screenshots ->
                // The label says SAVED, not sent, and that is the whole point: it is set once the
                // report is written, before the upload has even started, so "Diagnostics sent" was a
                // claim the app could not possibly have checked — and offline, or on a 5xx, it
                // was simply false. What HAS happened by here is true and is what matters: the
                // report is written to disk, and every launch retries it until the sink accepts
                // it. Dewi has to be able to believe this row; it is the one he uses to tell us
                // something is wrong.
                asking = false
                diagnosticsSaving.value = true
                container.applicationScope.launch {
                    val note = diagnosticsNote(typed, fallback)
                    withContext(Dispatchers.IO) { container.sendDiagnostics(note, screenshots) }
                    diagnosticsSaving.value = false
                    sent = true
                }
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !sent && !saving) { asking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when {
                        sent -> R.string.settings_diagnostics_sent
                        saving -> R.string.settings_diagnostics_saving
                        toGitHub -> R.string.report_github_row
                        else -> R.string.settings_diagnostics_send
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (toGitHub) R.string.report_github_row_summary else R.string.settings_diagnostics_summary
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    inset: Dp = 16.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QualityRow(label: String, current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { expanded = true }) {
            Text(labelFor(current))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            QUALITY_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option.height)
                        expanded = false
                    },
                )
            }
        }
    }
}
