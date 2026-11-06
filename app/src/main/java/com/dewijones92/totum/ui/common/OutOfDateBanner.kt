package com.dewijones92.totum.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.update.AppUpdateCheck
import com.dewijones92.totum.data.update.AppVersion
import com.dewijones92.totum.data.update.Release
import com.dewijones92.totum.data.update.UpdateStatus
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.update.AppUpdateInstaller
import com.dewijones92.totum.update.UpdateInstall
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun OutOfDateBanner(check: AppUpdateCheck, installer: AppUpdateInstaller?, modifier: Modifier = Modifier) {
    val status by check.status.collectAsStateWithLifecycle()
    val install by (installer?.state ?: NO_INSTALL).collectAsStateWithLifecycle()
    val context = LocalContext.current
    (status as? UpdateStatus.OutOfDate)?.let { outOfDate ->
        val release = outOfDate.latest
        val viaObtainium = obtainiumLaunch(context) != null
        OutOfDateBanner(
            outOfDate,
            install,
            BannerActions(
                download = installer?.takeIf { release.apk != null }?.let { { it.start(release) } },
                install = { installer?.install(context) },
                cancel = { installer?.cancel() },
                other = { openUpdater(context, release.page) },
                otherLabel = if (viaObtainium) R.string.out_of_date_obtainium else R.string.out_of_date_release_page,
            ),
            modifier,
        )
    }
}

class BannerActions(
    val download: (() -> Unit)?,
    val install: () -> Unit,
    val cancel: () -> Unit,
    val other: () -> Unit,
    @StringRes val otherLabel: Int,
)

@Composable
fun OutOfDateBanner(
    status: UpdateStatus.OutOfDate,
    install: UpdateInstall,
    actions: BannerActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = modifier.fillMaxWidth().testTag(OUT_OF_DATE_BANNER_TAG),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.out_of_date_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(detailOf(status, install), style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) { BannerButtons(install, actions) }
        }
    }
}

@Composable
private fun detailOf(status: UpdateStatus.OutOfDate, install: UpdateInstall): String {
    val latest = status.latest.version.toString()
    return when (install) {
        UpdateInstall.Idle ->
            stringResource(R.string.out_of_date_detail, status.installed.toString(), latest)
        is UpdateInstall.Downloading ->
            install.percent
                ?.let { stringResource(R.string.out_of_date_downloading_percent, latest, it) }
                ?: stringResource(R.string.out_of_date_downloading, latest)
        is UpdateInstall.Ready -> stringResource(R.string.out_of_date_ready, latest) +
            if (install.allowAsked) "\n" + stringResource(R.string.out_of_date_allow) else ""
        is UpdateInstall.Refused -> stringResource(R.string.out_of_date_refused, install.why)
        is UpdateInstall.Failed -> stringResource(R.string.out_of_date_failed, install.why)
    }
}

@Composable
private fun BannerButtons(install: UpdateInstall, actions: BannerActions) {
    val download = actions.download
    when {
        download == null -> BannerButton(R.string.out_of_date_update, actions.other)
        install is UpdateInstall.Downloading -> BannerTextButton(R.string.cancel, actions.cancel)
        install is UpdateInstall.Ready -> BannerButton(R.string.out_of_date_install, actions.install)
        else -> {
            val retry = install is UpdateInstall.Refused || install is UpdateInstall.Failed
            BannerButton(if (retry) R.string.out_of_date_retry else R.string.out_of_date_download, download)
            BannerTextButton(actions.otherLabel, actions.other)
        }
    }
}

@Composable
private fun BannerButton(@StringRes label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onError,
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) { Text(stringResource(label), fontWeight = FontWeight.Bold) }
}

@Composable
private fun BannerTextButton(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError),
    ) { Text(stringResource(label)) }
}

const val OUT_OF_DATE_BANNER_TAG = "out-of-date-banner"

private val UPDATER_PACKAGES = listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid")

private val NO_INSTALL = MutableStateFlow<UpdateInstall>(UpdateInstall.Idle)

private fun obtainiumLaunch(context: Context): Intent? =
    UPDATER_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

private fun openUpdater(context: Context, releasePage: HttpUrl) {
    val obtainium = obtainiumLaunch(context)
    val intent = obtainium ?: Intent(Intent.ACTION_VIEW, releasePage.value.toUri())
    Diag.log("update", "update tapped -> ${if (obtainium != null) "opening Obtainium" else "opening $releasePage"}")
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Diag.warn("update", "nothing could open the update", e)
    }
}

@Preview
@Composable
private fun OutOfDateBannerPreview() {
    TotumTheme {
        OutOfDateBanner(
            UpdateStatus.OutOfDate(
                AppVersion.parse("0.1.602")!!,
                Release(AppVersion.parse("0.1.603")!!, "v0.1.603", HttpUrl.of("https://github.com"), null),
            ),
            UpdateInstall.Idle,
            BannerActions({}, {}, {}, {}, R.string.out_of_date_obtainium),
        )
    }
}
