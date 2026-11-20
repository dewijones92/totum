package com.dewijones92.totum.ui.library

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.KindAccess
import com.dewijones92.totum.data.phone.PhoneMediaAccess
import com.dewijones92.totum.data.phone.PhoneMediaPermissions
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.WrappingTabs

@Composable
fun PhoneMediaScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val permissions = container.phoneMedia.permissions
    var access by remember { mutableStateOf(permissions.access()) }
    var asked by remember { mutableStateOf(permissions.askedBefore()) }
    val refresh = {
        access = permissions.access()
        asked = permissions.askedBefore()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val answered = result.entries.joinToString { (name, granted) -> "${name.substringAfterLast('.')}=$granted" }
        Diag.log("phone-media", "the permission dialog answered: $answered")
        permissions.noteAsked()
        refresh()
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        Diag.log(
            "phone-media",
            "the page resumed; ${PhoneMediaPermissions.reason(
                PhoneMediaPermissions.gate(permissions.access(), permissions.askedBefore()),
                permissions.access()
            )}"
        )
        onPauseOrDispose {}
    }
    var tabName by rememberSaveable { mutableStateOf(PhoneTab.VIDEOS.name) }
    val tab = PhoneTab.valueOf(tabName)
    var viewing by remember { mutableStateOf<PhotoViewing?>(null) }
    val screens = remember(container) {
        PhotoScreens(container.playbackController, container.phoneMedia.screenPictures, container.operations)
    }
    val context = LocalContext.current
    val ask = {
        Diag.log("phone-media", "asking for media permissions from the ${tab.name} tab")
        request.launch(PhoneMediaPermissions.toAsk.toTypedArray())
    }
    val openSettings = {
        Diag.log("phone-media", "opening Totum's settings page so media permissions can be changed")
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
    BackHandler(enabled = viewing != null) { viewing = null }
    Box(modifier.fillMaxSize()) {
        PhoneMediaContent(container, tab, access, asked, ask, openSettings, onBack, onTab = { tabName = it.name }) {
            viewing = it
        }
        viewing?.let { PhotoViewer(it, screens, onClose = { viewing = null }) }
    }
}

@Composable
private fun PhoneMediaContent(
    container: AppContainer,
    tab: PhoneTab,
    access: PhoneMediaAccess,
    asked: Boolean,
    ask: () -> Unit,
    openSettings: () -> Unit,
    onBack: () -> Unit,
    onTab: (PhoneTab) -> Unit,
    onOpenPhoto: (PhotoViewing) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.phone_media_title), onBack)
        WrappingTabs(
            tabs = PhoneTab.entries,
            selected = tab,
            onSelect = {
                Diag.log("phone-media", "tab ${it.name} chosen")
                onTab(it)
            },
            tag = { it.tag },
        ) { Text(stringResource(it.label()), style = MaterialTheme.typography.titleSmall) }
        when (tab) {
            PhoneTab.VIDEOS -> DeviceTab(container, PhoneMediaKind.VIDEO, access, asked, ask, openSettings, onOpenPhoto)
            PhoneTab.AUDIO -> DeviceTab(container, PhoneMediaKind.AUDIO, access, asked, ask, openSettings, onOpenPhoto)
            PhoneTab.PHOTOS -> DeviceTab(container, PhoneMediaKind.PHOTO, access, asked, ask, openSettings, onOpenPhoto)
            PhoneTab.GOOGLE -> GooglePhotosTab(container, onOpenPhoto)
        }
    }
}

private fun PhoneTab.label(): Int = when (this) {
    PhoneTab.VIDEOS -> R.string.phone_tab_videos
    PhoneTab.AUDIO -> R.string.phone_tab_audio
    PhoneTab.PHOTOS -> R.string.phone_tab_photos
    PhoneTab.GOOGLE -> R.string.phone_tab_google
}

@Composable
private fun DeviceTab(
    container: AppContainer,
    kind: PhoneMediaKind,
    access: PhoneMediaAccess,
    asked: Boolean,
    ask: () -> Unit,
    openSettings: () -> Unit,
    onOpenPhoto: (PhotoViewing) -> Unit,
) {
    when (val mine = access.of(kind)) {
        KindAccess.NONE -> if (asked) BlockedCard(kind, openSettings) else AskCard(ask)
        KindAccess.FULL, KindAccess.PARTIAL -> {
            Column(Modifier.fillMaxSize()) {
                if (mine == KindAccess.PARTIAL) PartialBanner(ask)
                when (kind) {
                    PhoneMediaKind.PHOTO -> PhotoGridTab(container, container.phoneMedia.device, onOpenPhoto)
                    else -> MediaListTab(container, kind, container.phoneMedia.device)
                }
            }
        }
    }
}

@Composable
private fun AskCard(ask: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(stringResource(R.string.phone_ask_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.phone_ask_body),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = ask, modifier = Modifier.padding(top = 12.dp).testTag(PHONE_ALLOW_TAG)) {
            Text(stringResource(R.string.phone_ask_button))
        }
    }
}

@Composable
private fun BlockedCard(kind: PhoneMediaKind, openSettings: () -> Unit) {
    val what = stringResource(
        when (kind) {
            PhoneMediaKind.VIDEO -> R.string.phone_kind_videos
            PhoneMediaKind.AUDIO -> R.string.phone_kind_audio
            PhoneMediaKind.PHOTO -> R.string.phone_kind_photos
        },
    )
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(stringResource(R.string.phone_blocked_title, what), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.phone_blocked_body),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = openSettings, modifier = Modifier.padding(top = 12.dp).testTag(PHONE_SETTINGS_TAG)) {
            Text(stringResource(R.string.phone_blocked_button))
        }
    }
}

@Composable
private fun PartialBanner(ask: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.phone_partial_banner), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = ask) { Text(stringResource(R.string.phone_partial_more)) }
    }
}

const val PHONE_ALLOW_TAG = "phone-media-allow"
const val PHONE_SETTINGS_TAG = "phone-media-settings"
