package com.dewijones92.totum.diagnostics

import android.content.res.Resources
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val SEND_STATE_KEY = "send-state"
private const val BYTES_PER_KB = 1024

private fun Int.inKb(): Int = (this / BYTES_PER_KB).coerceAtLeast(1)

internal fun StateSend.message(resources: Resources): String = when (this) {
    is StateSend.Sent -> resources.getString(R.string.settings_send_state_sent, bytes.inKb())
    StateSend.NotSignedIn -> resources.getString(R.string.settings_send_state_no_sign_in)
    StateSend.NotGathered -> resources.getString(R.string.settings_send_state_not_gathered)
    StateSend.Refused -> resources.getString(R.string.settings_send_state_refused)
    StateSend.TestRun -> resources.getString(R.string.settings_send_state_test_run)
}

internal suspend fun AppContainer.sendMyState(resources: Resources, from: String): StateSend {
    Diag.log("diagnostics", "dewidebug send my state asked for from $from")
    var outcome: StateSend = StateSend.NotGathered
    operations.tracking(SEND_STATE_KEY, OperationKind.TASK, resources.getString(R.string.settings_send_state_sending)) {
        outcome = suspendCoroutine { next -> homeReports.sendState { next.resume(it) } }
        when (val done = outcome) {
            is StateSend.Sent -> OperationEnd.Done(1)
            else -> OperationEnd.Failed(done.toString())
        }
    }
    Diag.log("diagnostics", "dewidebug send my state from $from ended: $outcome")
    return outcome
}
