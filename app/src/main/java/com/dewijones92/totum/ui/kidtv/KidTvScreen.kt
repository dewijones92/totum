package com.dewijones92.totum.ui.kidtv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.kidlock.KidTv
import com.dewijones92.totum.kidlock.KidTvStatus
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.ui.cast.LOCAL_NETWORK
import com.dewijones92.totum.ui.cast.needsLocalNetwork
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.kindWords
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.dewijones92.totum.pins.titleThrough
import com.dewijones92.totum.ui.common.LocalItemShield

@Composable
fun KidTvScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val kidTv = container.kidTv ?: return
    val choice by kidTv.choice.collectAsStateWithLifecycle()
    val status by kidTv.status.collectAsStateWithLifecycle()
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    val candidates = remember(pins) { pins.filter { it.mayShowPicture } }
    KidTvToasts(status)
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.kidtv_title), onBack)
            Text(
                text = stringResource(R.string.kidtv_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val pinTitle = pins.firstOrNull { it.key == choice.pinKey }?.title
            Text(
                text = kidTvStatusText(status, pinTitle, choice.tvName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("kidtv-status"),
            )
            StartStop(kidTv, status)
            PlaylistChoices(kidTv, candidates, choice.pinKey)
            TvChoices(kidTv, choice.tvName)
        }
    }
}

@Composable
private fun StartStop(kidTv: KidTv, status: KidTvStatus) {
    val on = status is KidTvStatus.On || status is KidTvStatus.Starting
    Button(
        onClick = { kidTv.tap("the Kid TV page") },
        enabled = status !is KidTvStatus.NotSetUp && status !is KidTvStatus.Starting,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("kidtv-start"),
    ) {
        Text(stringResource(if (on) R.string.kidtv_stop else R.string.kidtv_start))
    }
}

@Composable
private fun PlaylistChoices(kidTv: KidTv, candidates: List<Pin>, chosenKey: String?) {
    Text(
        text = stringResource(R.string.kidtv_playlist_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (candidates.isEmpty()) {
        Text(
            text = stringResource(R.string.kidtv_no_pins),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    candidates.forEach { pin ->
        ChoiceRow(
            selected = pin.key == chosenKey,
            title = pin.titleThrough(LocalItemShield.current),
            detail = stringResource(pin.kindWords()),
            onSelect = { kidTv.choosePin(pin.key, "the Kid TV page") },
        )
    }
}

@Composable
private fun TvChoices(kidTv: KidTv, chosenTv: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seen by remember { mutableStateOf(emptyList<String>()) }
    var scanning by remember { mutableStateOf(false) }
    val scan: () -> Unit = {
        scanning = true
        scope.launch {
            withTimeoutOrNull(SCAN_MS) { kidTv.scan.collect { seen = it } }
            Diag.log("kidlock", "TV scan from the Kid TV page ended: seen $seen")
            scanning = false
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Diag.log("kidlock", "local network access ${if (granted) "granted" else "refused"} on the Kid TV page")
        if (granted) scan()
    }
    Text(
        text = stringResource(R.string.kidtv_tv_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val names = (listOfNotNull(chosenTv) + seen).distinctBy { it.lowercase() }
    names.forEach { name ->
        ChoiceRow(
            selected = name.equals(chosenTv, ignoreCase = true),
            title = name,
            detail = null,
            onSelect = { kidTv.chooseTv(name, "the Kid TV page") },
        )
    }
    if (seen.isEmpty() && !scanning) {
        Text(
            text = stringResource(R.string.kidtv_none_seen),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    OutlinedButton(
        onClick = { if (needsLocalNetwork(context)) ask.launch(LOCAL_NETWORK) else scan() },
        enabled = !scanning,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("kidtv-scan"),
    ) {
        Text(stringResource(if (scanning) R.string.kidtv_scanning else R.string.kidtv_scan))
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String?, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SCAN_MS = 8_000L
