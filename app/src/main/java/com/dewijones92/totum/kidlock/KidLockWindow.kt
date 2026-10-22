package com.dewijones92.totum.kidlock

import android.app.ActivityManager
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KidLockWindow(private val activity: FragmentActivity, private val kidLock: KidLock) {

    private var wasLocked = false
    private var hinted = false

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kidLock.locked.collectLatest { locked ->
                    if (locked) {
                        wasLocked = true
                        holdLocked()
                    } else if (wasLocked) {
                        wasLocked = false
                        release()
                    }
                }
            }
        }
    }

    private suspend fun holdLocked() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay(ROTATION_SETTLES_MS)
        Diag.log(TAG, "asking Android to pin the app now the screen has turned (its confirmation closes on rotation)")
        runCatching { activity.startLockTask() }
            .onFailure { Diag.warn(TAG, "Android refused to pin the app; the in-app lock still holds", it) }
        var everPinned = false
        var waitedMs = 0L
        while (true) {
            val pinned = lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE
            kidLock.systemPinned(pinned, "checked after ${waitedMs}ms")
            when {
                pinned -> everPinned = true
                everPinned -> {
                    kidLock.unlock(
                        "Android's pin was lifted (its unpin gesture, with the phone's PIN when that is set)"
                    )
                    return
                }
                waitedMs == PIN_WAIT_MS && !hinted -> {
                    hinted = true
                    Diag.log(
                        TAG,
                        "not pinned after ${PIN_WAIT_MS}ms: app pinning is off, declined, or still being asked"
                    )
                    Toast.makeText(activity, R.string.kid_lock_pinning_off, Toast.LENGTH_LONG).show()
                }
            }
            delay(CHECK_EVERY_MS)
            waitedMs += CHECK_EVERY_MS
        }
    }

    private fun release() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { activity.stopLockTask() }.onFailure { Diag.warn(TAG, "could not unpin the app", it) }
        }
        hinted = false
        Diag.log(TAG, "window released: the screen may sleep again, Android's pin lifted")
    }

    private fun lockTaskState(): Int =
        activity.getSystemService(ActivityManager::class.java)?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE

    private companion object {
        const val TAG = "kidlock"
        const val CHECK_EVERY_MS = 1_000L
        const val PIN_WAIT_MS = 30_000L
        const val ROTATION_SETTLES_MS = 1_500L
    }
}
