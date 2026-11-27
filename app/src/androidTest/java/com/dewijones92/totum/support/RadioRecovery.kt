package com.dewijones92.totum.support

class RadioRecovery(
    private val enable: () -> Unit,
    private val disable: () -> Unit,
    private val hasNetwork: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val log: (String) -> Unit,
) {

    fun bringBack(): Boolean {
        repeat(ATTEMPTS) { attempt ->
            enable()
            if (waitForNetwork()) {
                log("network back after attempt ${attempt + 1} of $ATTEMPTS")
                return true
            }
            log("attempt ${attempt + 1} of $ATTEMPTS: no network within ${WAIT_MS}ms of switching the radios on")
            if (attempt + 1 < ATTEMPTS) {
                disable()
                pause(CYCLE_PAUSE_MS)
            }
        }
        return false
    }

    private fun waitForNetwork(): Boolean {
        var waited = 0L
        while (waited < WAIT_MS) {
            if (hasNetwork()) return true
            pause(POLL_MS)
            waited += POLL_MS
        }
        return hasNetwork()
    }

    companion object {
        const val ATTEMPTS = 3
        const val WAIT_MS = 25_000L
        const val CYCLE_PAUSE_MS = 4_000L
        const val POLL_MS = 200L
    }
}
