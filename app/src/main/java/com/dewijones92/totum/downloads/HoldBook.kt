package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.Diag

internal class HoldBook {
    private var pendingStarts = 0
    private var releaseWhenStarted = false

    @Synchronized
    fun hold(holding: Boolean, start: () -> Unit, stop: () -> Unit) {
        when {
            holding -> {
                releaseWhenStarted = false
                start()
                pendingStarts++
            }
            pendingStarts > 0 -> {
                releaseWhenStarted = true
                Diag.log(
                    "downloads",
                    "hold released before the service started ($pendingStarts start(s) pending); " +
                        "it lets go once started, since stopping it sooner crashes the app",
                )
            }
            else -> stop()
        }
    }

    @Synchronized
    fun arrived(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        return (releaseWhenStarted && pendingStarts == 0).also { if (it) releaseWhenStarted = false }
    }
}
