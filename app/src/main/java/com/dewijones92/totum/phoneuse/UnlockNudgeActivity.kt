package com.dewijones92.totum.phoneuse

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.stats.UnlockNudgeWhat
import com.dewijones92.totum.theme.TotumTheme
import java.lang.ref.WeakReference

class UnlockNudgeActivity : ComponentActivity() {
    private val phoneUse get() = (application as TotumApplication).container.phoneUse
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val request = NudgeRequest(
            sinceLastMs = intent.getLongExtra(EXTRA_SINCE_MS, 0),
            unlocksToday = intent.getIntExtra(EXTRA_TODAY, 0),
            shownAt = intent.getLongExtra(EXTRA_SHOWN_AT, System.currentTimeMillis()),
        )
        Diag.log(PhoneUse.TAG, "dewidebug phoneuse nudge screen shown: $request")
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leave("back")
            },
        )
        setContent {
            TotumTheme(darkTheme = false) {
                UnlockNudgeScreen(
                    request = request,
                    now = System::currentTimeMillis,
                    onContinue = ::carryOn,
                    onHome = { leave("home button", goHome = true) },
                    onIdle = {
                        Diag.log(PhoneUse.TAG, "dewidebug phoneuse nudge screen left alone for too long; closing")
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UnlockNudgeNotifications(this).cancel()
    }

    override fun onStop() {
        if (!isFinishing) {
            Diag.log(
                PhoneUse.TAG,
                "dewidebug phoneuse nudge screen stopped without an answer (answered=$answered); closing"
            )
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun carryOn() {
        record(UnlockNudgeWhat.CONTINUED)
        finish()
    }

    private fun leave(how: String, goHome: Boolean = false) {
        Diag.log(PhoneUse.TAG, "dewidebug phoneuse nudge screen left by $how")
        record(UnlockNudgeWhat.LEFT)
        if (goHome) {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    private fun record(what: UnlockNudgeWhat) {
        if (answered) return
        answered = true
        phoneUse.nudgeAnswered(what)
    }

    companion object {
        private const val EXTRA_SINCE_MS = "phoneuse.sinceMs"
        private const val EXTRA_TODAY = "phoneuse.today"
        private const val EXTRA_SHOWN_AT = "phoneuse.shownAt"
        private const val REQUEST_SHOW = 7341
        private var current: WeakReference<Activity>? = null

        fun intent(context: Context, request: NudgeRequest): Intent = Intent(context, UnlockNudgeActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
            .putExtra(EXTRA_SINCE_MS, request.sinceLastMs)
            .putExtra(EXTRA_TODAY, request.unlocksToday)
            .putExtra(EXTRA_SHOWN_AT, request.shownAt)

        fun pending(context: Context, request: NudgeRequest): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun finishAll() {
            current?.get()?.finish()
        }
    }
}
