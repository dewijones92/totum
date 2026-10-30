package com.dewijones92.totum.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dewijones92.totum.common.Diag
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class MainThreadWatch(
    private val stalls: MainThreadStalls = MainThreadStalls(log = { Diag.warn("main", it) }),
    private val tickMs: Long = TICK_MS,
) : Application.ActivityLifecycleCallbacks {
    private val lock = Object()
    private var started = 0

    fun start(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        thread(name = "main-watch", isDaemon = true) {
            runCatching { watch() }.onFailure { Diag.warn("main", "the main-thread watch stopped", it) }
        }
        Diag.log(
            "main",
            "watching the main thread while a screen is shown: " +
                "a stall over ${MainThreadStalls.REPORT_OVER_MS}ms is logged with where it was",
        )
    }

    private fun watch() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        while (true) {
            synchronized(lock) { while (started == 0) lock.wait() }
            val ranAt = AtomicLong(0)
            val postedAt = SystemClock.uptimeMillis()
            main.post { ranAt.set(SystemClock.uptimeMillis()) }
            Thread.sleep(tickMs)
            while (ranAt.get() == 0L) {
                stalls.sampled(mainThread.stackTrace)
                Thread.sleep(tickMs)
            }
            stalls.ended(ranAt.get() - postedAt, SystemClock.uptimeMillis())
        }
    }

    override fun onActivityStarted(activity: Activity) = synchronized(lock) {
        started++
        lock.notifyAll()
    }

    override fun onActivityStopped(activity: Activity) = synchronized(lock) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TICK_MS = 100L
    }
}
