package com.dewijones92.totum.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turning this device's radios off, and knowing when the OS agrees.
 *
 * One copy for every test that needs it, because getting it wrong looks like a pass. Three tests
 * now take a device offline and each had its own version of these six lines.
 *
 * **The radios go off, not a packet filter.** `iptables -j DROP` leaves Android reporting the
 * network as VALIDATED, so every connectivity-aware path — `NetworkCallback`, wait-for-online,
 * "retry when back" — carries on believing it is connected and is never exercised. That cost a day
 * on 2026-07-31: the filtered run looked like a successful reproduction while leaving the code
 * under test untouched. `svc wifi disable` is what makes the OS agree.
 */
object DeviceRadios {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** Both radios off, waiting until the OS stops reporting a validated network. */
    fun goOffline() {
        shell("svc wifi disable")
        shell("svc data disable")
        // The callbacks are asynchronous; acting before the OS has settled races the very state
        // the test is about.
        runBlocking {
            withTimeoutOrNull(OFFLINE_TIMEOUT_MS) { while (hasNetwork()) delay(POLL_MS) }
        }
    }

    /**
     * Both radios back on, **waiting until the OS agrees** — the mirror of [goOffline].
     *
     * The wait is the whole point and it was missing, which made this the asymmetry that mattered.
     * Turning the radios off blocked until the OS stopped reporting a validated network; turning
     * them back on returned immediately, so the next test began while Android still believed it was
     * offline. `PlayRoute` then correctly refused to stream — *"not downloaded and there is no
     * network"* — and the failure surfaced as "the torrent never started playing", nowhere near the
     * test that had actually left the device that way.
     *
     * It failed four CI runs in a row and never once here: re-validating a network is slower on a
     * cold emulator, so locally the race was simply won every time. Diagnosed 2026-08-07 from the
     * trail the failure now prints, which named the refusal outright.
     */
    fun goOnline() {
        val recovery = RadioRecovery(
            enable = {
                shell("svc wifi enable")
                shell("svc data enable")
            },
            disable = {
                shell("svc wifi disable")
                shell("svc data disable")
            },
            hasNetwork = ::hasNetwork,
            pause = { Thread.sleep(it) },
            log = { Log.w("dewidebug", "DeviceRadios: $it") },
        )
        check(recovery.bringBack()) {
            "DeviceRadios: the device's network did not come back after ${RadioRecovery.ATTEMPTS} attempts of " +
                "${RadioRecovery.WAIT_MS}ms, so every later test that needs one would fail for this reason, " +
                "not its own (2026-11-25: Wi-Fi switched back on 1.3 s after being switched off never reconnected; " +
                "six later tests failed and none of them said why)"
        }
    }

    fun goUnmetered() {
        shell("svc wifi enable")
        shell("svc data disable")
        runBlocking {
            val ready = withTimeoutOrNull(ONLINE_TIMEOUT_MS) { while (!hasUnmeteredNetwork()) delay(POLL_MS) }
            if (ready == null) {
                println("DeviceRadios: no unmetered network within ${ONLINE_TIMEOUT_MS}ms; timings may be disturbed")
            }
        }
    }

    private fun hasUnmeteredNetwork(): Boolean {
        val manager = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** What the app itself consults, so a test can assert the device really is offline. */
    fun hasNetwork(): Boolean {
        val context: Context = instrumentation.targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private const val OFFLINE_TIMEOUT_MS = 15_000L

    /** Longer than going off: a cold emulator can take a while to re-validate a network. */
    private const val ONLINE_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 200L
}
