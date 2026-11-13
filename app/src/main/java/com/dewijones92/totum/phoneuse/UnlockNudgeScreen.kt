package com.dewijones92.totum.phoneuse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.stats.continueInSeconds
import com.dewijones92.totum.theme.LocalLaurel
import kotlinx.coroutines.delay

@Composable
fun UnlockNudgeScreen(
    request: NudgeRequest,
    now: () -> Long,
    onContinue: () -> Unit,
    onHome: () -> Unit,
    onIdle: () -> Unit,
) {
    KeepScreenAwake("unlock nudge")
    val context = LocalContext.current
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(request) {
        while (true) {
            clock = now()
            delay(TICK_MS)
        }
    }
    LaunchedEffect(request) {
        delay(IDLE_CLOSE_MS)
        onIdle()
    }
    val waitSeconds = continueInSeconds(request.shownAt, clock)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Medallion()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.phone_use_nudge_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("unlock-nudge-title"),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    context.nudgeLine(request),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("unlock-nudge-line"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.phone_use_nudge_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Buttons(waitSeconds, onContinue, onHome)
            }
        }
    }
}

@Composable
private fun Buttons(waitSeconds: Int, onContinue: () -> Unit, onHome: () -> Unit) {
    OutlinedButton(
        onClick = onHome,
        modifier = Modifier.fillMaxWidth().height(HOME_HEIGHT).testTag("unlock-nudge-home"),
    ) {
        Text(
            stringResource(R.string.phone_use_nudge_home),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onContinue,
        enabled = waitSeconds == 0,
        modifier = Modifier.fillMaxWidth().height(CONTINUE_HEIGHT).testTag("unlock-nudge-continue"),
    ) {
        Text(
            if (waitSeconds == 0) {
                stringResource(R.string.phone_use_nudge_continue)
            } else {
                stringResource(R.string.phone_use_nudge_continue_in, waitSeconds)
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Medallion() {
    val laurel = LocalLaurel.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MEDALLION_SIZE)
            .background(laurel.container, CircleShape)
            .border(MEDALLION_STROKE, laurel.accent, CircleShape),
    ) {
        Text("📱", fontSize = 72.sp)
    }
}

private const val TICK_MS = 250L
private const val IDLE_CLOSE_MS = 90_000L
private val MEDALLION_SIZE = 160.dp
private val MEDALLION_STROKE = 4.dp
private val HOME_HEIGHT = 72.dp
private val CONTINUE_HEIGHT = 56.dp
