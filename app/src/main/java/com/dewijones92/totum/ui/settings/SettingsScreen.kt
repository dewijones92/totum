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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.sponsorblock.SkipCategory
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.NOTE_MAX_CHARS
import com.dewijones92.totum.diagnostics.diagnosticsNote
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.importexport.ImportExportScreen

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
    var showImportExport by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    if (showImportExport) {
        ImportExportScreen(container, onBack = { showImportExport = false }, modifier = modifier)
        return
    }
    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false }, modifier = modifier)
        return
    }

    Surface(modifier = modifier.fillMaxSize()) {
        // Scrolls: the screen already ran past the bottom of a phone once the
        // SponsorBlock categories were added, and everything below was simply unreachable.
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            QualitySection(settings, prefs)
            DownloadSettings(settings, prefs)
            Text(
                text = stringResource(R.string.settings_subscriptions_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            NavRow(
                label = stringResource(R.string.settings_import_export),
                onClick = { showImportExport = true },
            )
            SectionTitle(stringResource(R.string.settings_diagnostics_section))
            SkipCategorySection(settings.skipCategories, prefs::setSkipCategories)

            ViewDiagnosticsRow(onOpen = { showDiagnostics = true })
            HomeServerRow(container)
            CheckForContentRow(container)
            DiagnosticsRow(container)
        }
    }
}

/** Automatic-download preferences: whether, and on which networks. */
@Composable
private fun DownloadSettings(settings: AppPreferences.Settings, prefs: AppPreferences) {
    SectionTitle(stringResource(R.string.settings_downloads_section))
    SwitchRow(
        label = stringResource(R.string.settings_auto_download),
        summary = stringResource(R.string.settings_auto_download_summary),
        checked = settings.autoDownloadQueue,
        onCheckedChange = prefs::setAutoDownloadQueue,
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_download_wifi),
        summary = stringResource(R.string.settings_auto_download_wifi_summary),
        checked = settings.autoDownloadWifiOnly,
        onCheckedChange = prefs::setAutoDownloadWifiOnly,
        enabled = settings.autoDownloadQueue,
    )
}

/**
 * Sends the current state and event trail to the crash sink. Deliberately available
 * without a crash: most defects are "it behaved wrongly", not "it died".
 */
/** The per-network quality caps: the quality auto-picked when a video starts. */
@Composable
private fun QualitySection(settings: AppPreferences.Settings, prefs: AppPreferences) {
    Text(
        text = stringResource(R.string.settings_quality_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    QualityRow(
        label = stringResource(R.string.settings_quality_wifi),
        current = settings.wifiMaxHeight,
        onSelect = prefs::setWifiMaxHeight,
    )
    QualityRow(
        label = stringResource(R.string.settings_quality_cellular),
        current = settings.cellularMaxHeight,
        onSelect = prefs::setCellularMaxHeight,
    )
}

/** The SponsorBlock categories, each independently on or off. */
@Composable
private fun SkipCategorySection(enabled: Set<SkipCategory>, onChange: (Set<SkipCategory>) -> Unit) {
    Text(
        text = stringResource(R.string.settings_skip_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        text = stringResource(R.string.settings_skip_supporting),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SkipCategory.entries.forEach { category ->
        SkipCategoryRow(
            category = category,
            enabled = category in enabled,
            onToggle = { on -> onChange(if (on) enabled + category else enabled - category) },
        )
    }
}

/**
 * One SponsorBlock category. Offered individually because they are not equivalent: a
 * sponsor read is unambiguously not the content, whereas an intro or a recap is content
 * to some people — so the opinionated ones are opt-in rather than assumed.
 */
@Composable
private fun SkipCategoryRow(category: SkipCategory, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(category.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

private fun SkipCategory.labelRes(): Int = when (this) {
    SkipCategory.SPONSOR -> R.string.skip_sponsor
    SkipCategory.SELF_PROMO -> R.string.skip_self_promo
    SkipCategory.INTERACTION -> R.string.skip_interaction
    SkipCategory.INTRO -> R.string.skip_intro
    SkipCategory.OUTRO -> R.string.skip_outro
    SkipCategory.PREVIEW -> R.string.skip_preview
    SkipCategory.MUSIC_OFFTOPIC -> R.string.skip_music_offtopic
    SkipCategory.FILLER -> R.string.skip_filler
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

@Composable
private fun DiagnosticsRow(container: AppContainer) {
    var sent by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val fallback = stringResource(R.string.settings_diagnostics_note)

    if (asking) {
        DiagnosticsNoteDialog(
            onDismiss = { asking = false },
            onSend = { typed ->
                container.sendDiagnostics(diagnosticsNote(typed, fallback))
                asking = false
                sent = true
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !sent) { asking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (sent) R.string.settings_diagnostics_sent else R.string.settings_diagnostics_send,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_diagnostics_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Asks what went wrong before sending, because the reader's problem is knowing WHICH of four
 * hundred events to look at.
 *
 * Sending is never blocked on it — an empty box still sends, and that is deliberate: the worst
 * outcome here is a report that does not get sent because writing a description felt like work.
 * See [diagnosticsNote].
 */
@Composable
private fun DiagnosticsNoteDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_diagnostics_note_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_diagnostics_note_supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(NOTE_MAX_CHARS) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("diagnostics-note"),
                    placeholder = { Text(stringResource(R.string.settings_diagnostics_note_hint)) },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(typed) }) {
                Text(stringResource(R.string.settings_diagnostics_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
private fun NavRow(label: String, onClick: () -> Unit) {
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
