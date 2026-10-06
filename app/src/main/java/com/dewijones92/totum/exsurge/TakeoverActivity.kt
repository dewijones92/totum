package com.dewijones92.totum.exsurge

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.theme.TotumTheme
import java.lang.ref.WeakReference
import java.time.Duration

class TakeoverActivity : ComponentActivity() {
    private val exsurge get() = (application as TotumApplication).container.exsurge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        current = WeakReference(this)
        val goNow = intent.getBooleanExtra(EXTRA_GO, false)
        val state = exsurge.view.value.memory.state.label()
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge takeover shown state=$state go=$goNow")
        if (goNow) go()
        setContent {
            TotumTheme(darkTheme = false) {
                val view by exsurge.view.collectAsStateWithLifecycle()
                LaunchedEffect(view.memory.state) {
                    val state = view.memory.state
                    val summoning = state is ExsurgeState.Summoned || state is ExsurgeState.Snoozed
                    if (!summoning) finish()
                }
                val destination =
                    remember(view.settings.destinationPackage) { destinationLabel(view.settings.destinationPackage) }
                TakeoverScreen(
                    view = view,
                    destination = destination,
                    onGo = ::go,
                    onJustWalk = { justWalk() },
                    onContinueTotum = { startBreak(ExsurgeEvent.ContinueTotum) },
                    onSnooze = { exsurge.dispatch(ExsurgeEvent.Snooze, "takeover") },
                    onBreakMinutes = { minutes -> chooseBreak(exsurge, minutes) },
                    onSkip = { exsurge.dispatch(ExsurgeEvent.Skip, "takeover") },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ExsurgeNotifications(this).cancelSummons()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_GO, false)) go()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun destinationLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(if (packageName == LOQUAX_PACKAGE) "Loquax" else packageName)

    private fun go() = startBreak(ExsurgeEvent.Go)

    private fun startBreak(event: ExsurgeEvent) = afterUnlock(event.toString()) { how ->
        exsurge.dispatch(event, "takeover ($how)")
        finish()
    }

    private fun afterUnlock(action: String, proceed: (String) -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return proceed("unlocked")
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = proceed("keyguard dismissed")
                override fun onDismissCancelled() {
                    Diag.log(ExsurgeController.TAG, "dewidebug exsurge $action waiting: unlock cancelled")
                }
                override fun onDismissError() {
                    Diag.warn(ExsurgeController.TAG, "dewidebug exsurge $action: keyguard dismiss failed; going anyway")
                    proceed("keyguard error")
                }
            },
        )
    }

    private fun justWalk() {
        exsurge.dispatch(ExsurgeEvent.JustWalk, "takeover")
        finish()
    }

    companion object {
        private const val EXTRA_GO = "exsurge.go"
        private var current: WeakReference<Activity>? = null

        @Volatile
        var resumed: Boolean = false
            private set

        fun intent(context: Context, go: Boolean = false): Intent = Intent(context, TakeoverActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            .putExtra(EXTRA_GO, go)

        fun pending(context: Context, go: Boolean = false): PendingIntent = PendingIntent.getActivity(
            context,
            if (go) REQUEST_GO else REQUEST_SHOW,
            intent(context, go),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun finishAll() {
            current?.get()?.finish()
        }

        private const val REQUEST_SHOW = 7330
        private const val REQUEST_GO = 7331
    }
}

@Composable
fun TakeoverScreen(
    view: ExsurgeView,
    destination: String,
    onGo: () -> Unit,
    onJustWalk: () -> Unit,
    onContinueTotum: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onBreakMinutes: (Int) -> Unit = {},
) {
    val summoned = view.memory.state as? ExsurgeState.Summoned
    val snoozesLeft = snoozesLeft(view.memory.state, view.settings)
    val sat = (view.memory.state as? ExsurgeState.Summoned)?.let {
        Duration.between(it.summons.firstCalledAt, view.at).toMinutes() + view.settings.sittingMinutes
    }
        ?: view.settings.sittingMinutes.toLong()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val faceSize = minOf(FACE_SIZE, maxHeight * FACE_SHARE_OF_HEIGHT)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                SurgiusFace(Mood.SUMMONING, stringResource(R.string.exsurge_surgius), Modifier.size(faceSize))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.exsurge_takeover_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                summoned?.let {
                    Text(
                        stringResource(R.string.exsurge_takeover_call, it.call),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.exsurge_takeover_subtitle, sat.toInt(), view.settings.breakMinutes),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                BreakLengthChips(view.settings.breakMinutes, onBreakMinutes)
                Spacer(Modifier.height(20.dp))
                TakeoverButtons(
                    destination,
                    snoozesLeft,
                    view.settings.snoozeMinutes,
                    onGo,
                    onJustWalk,
                    onContinueTotum,
                    onSnooze,
                    onSkip
                )
            }
        }
    }
}

@Composable
private fun TakeoverButtons(
    destination: String,
    snoozesLeft: Int,
    snoozeMinutes: Int,
    onGo: () -> Unit,
    onJustWalk: () -> Unit,
    onContinueTotum: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
) {
    Button(
        onClick = onGo,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().height(96.dp).testTag("exsurge-go"),
    ) {
        Text(
            stringResource(R.string.exsurge_action_go_to, destination),
            fontSize = 32.sp,
            lineHeight = 38.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.ExtraBold
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onJustWalk, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("exsurge-walk")) {
        Text(stringResource(R.string.exsurge_action_just_walk), fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onContinueTotum,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("exsurge-continue")
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.exsurge_action_continue_totum),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.exsurge_action_continue_totum_detail),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onSnooze, enabled = snoozesLeft > 0, modifier = Modifier.testTag("exsurge-snooze")) {
        Text(
            if (snoozesLeft > 0) {
                stringResource(R.string.exsurge_action_snooze, snoozeMinutes, snoozesLeft)
            } else {
                stringResource(R.string.exsurge_action_snooze_none)
            },
        )
    }
    TextButton(onClick = onSkip, modifier = Modifier.testTag("exsurge-skip")) {
        Text(stringResource(R.string.exsurge_action_skip))
    }
}

@Composable
private fun BreakLengthChips(chosen: Int, onChoose: (Int) -> Unit) {
    Text(stringResource(R.string.exsurge_takeover_break_length), style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        breakChoices(chosen).forEach { minutes ->
            FilterChip(
                selected = minutes == chosen,
                onClick = { onChoose(minutes) },
                label = { Text(stringResource(R.string.exsurge_takeover_break_minutes, minutes)) },
                modifier = Modifier.testTag("exsurge-break-$minutes"),
            )
        }
    }
}

private fun chooseBreak(exsurge: ExsurgeController, minutes: Int) {
    Diag.log(ExsurgeController.TAG, "dewidebug exsurge takeover break length chosen=${minutes}m")
    exsurge.updateSettings("takeover chip") { it.copy(breakMinutes = minutes) }
}

internal fun breakChoices(chosen: Int): List<Int> = (BREAK_CHOICES + chosen).distinct().sorted()

private val BREAK_CHOICES = listOf(2, 5, 10, 15)
private val FACE_SIZE = 220.dp
private const val FACE_SHARE_OF_HEIGHT = 0.3f
