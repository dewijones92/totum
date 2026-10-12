package com.dewijones92.totum.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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

@Composable
fun OutOfDateBanner(check: AppUpdateCheck, modifier: Modifier = Modifier) {
    val status by check.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    (status as? UpdateStatus.OutOfDate)?.let { outOfDate ->
        OutOfDateBanner(outOfDate, onUpdate = { openUpdater(context, outOfDate.latest.page) }, modifier)
    }
}

@Composable
fun OutOfDateBanner(status: UpdateStatus.OutOfDate, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
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
                Text(
                    stringResource(
                        R.string.out_of_date_detail,
                        status.installed.toString(),
                        status.latest.version.toString()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onUpdate,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onError,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.out_of_date_update), fontWeight = FontWeight.Bold) }
        }
    }
}

const val OUT_OF_DATE_BANNER_TAG = "out-of-date-banner"

private val UPDATER_PACKAGES = listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid")

private fun openUpdater(context: Context, releasePage: HttpUrl) {
    val obtainium = UPDATER_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
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
            onUpdate = {},
        )
    }
}
