package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.update.UpdateStatus
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.polite.Politeness
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PoliteBanner(politeness: Politeness, signedIn: Boolean, padStatusBar: Boolean, modifier: Modifier = Modifier) {
    val slowDown by politeness.gate.slowDown.collectAsStateWithLifecycle()
    val botUntil by politeness.botBackOff.until.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(slowDown, botUntil) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }
    val slow = slowDown?.takeIf { it.untilMs > now }
    val bot = botUntil?.takeIf { it > now }
    val (title, detail) = when {
        slow != null -> stringResource(R.string.polite_slow_down_title) to
            stringResource(R.string.polite_slow_down_detail, clock(slow.untilMs), slow.reason)
        bot != null && signedIn -> stringResource(R.string.polite_bot_title) to
            stringResource(R.string.polite_bot_detail, clock(bot))
        bot != null -> stringResource(R.string.polite_bot_signed_out_title) to
            stringResource(R.string.polite_bot_signed_out_detail)
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().testTag(POLITE_BANNER_TAG),
    ) {
        Column(
            Modifier
                .then(if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun clock(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

const val POLITE_BANNER_TAG = "polite-banner"
private const val REFRESH_MS = 15_000L

@Composable
fun ShellBanners(container: AppContainer) {
    val update by container.appUpdate.status.collectAsStateWithLifecycle()
    val signedIn by container.accountSubscriptions.signedIn.collectAsStateWithLifecycle()
    val all by container.operations.running.collectAsStateWithLifecycle()
    val running = rememberLongRunning(all)
    RefusedTapToasts(container.playbackQueue, signedIn)
    OperationResultToasts(container.operations)
    Column {
        OutOfDateBanner(container.appUpdate)
        val first = update !is UpdateStatus.OutOfDate
        OperationsStrip(running, padStatusBar = first, onCancel = container.operations::cancel)
        PoliteBanner(container.polite, signedIn, padStatusBar = first && running.isEmpty())
    }
}
