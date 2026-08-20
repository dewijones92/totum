package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which recovery wins when a SABR stall and a dead network are the same event.
 *
 * They usually ARE the same event, and nothing pinned the order. `SabrPrematureEndException` is an
 * `IOException`, so a stall matches `isUnreachable()` as well — and a stall arrives fast in exactly the
 * case where waiting would have helped: a refused connection or a dead DNS fails in under a
 * millisecond, so a read spends its whole six-fetch budget within about a millisecond of airplane mode
 * starting, and gives up. Landing on `Rejected` means `REFUSED_MAX_ATTEMPTS` of one and no
 * `awaitNetwork`, so walking into a tunnel ends a SABR item on the spot, where an ordinary HTTP stream
 * would have waited for the signal to come back.
 *
 * That is a deliberate trade and not an accident, which is why it is written down here: an address
 * YouTube is refusing to serve wants a fresh resolve, not a wait, and that is the case the ordering was
 * written for. What was missing was any test at all, so the trade could be changed — or reintroduced —
 * without anyone noticing which way round it had been.
 *
 * The judgement is a separate function from the `PlaybackException` extension for the same reason
 * `isExpiredStatus` and `leaseVerdict` are: building a Media3 exception reads `SystemClock`, which a JVM
 * unit test cannot call.
 */
class SabrStallOutranksTheNetworkTest {

    /** THE pinned decision: both apply, and a fresh resolve is preferred to waiting. */
    @Test
    fun `a sabr stall during an outage asks for a fresh resolve rather than waiting`() {
        assertEquals(
            "a stall that reaches here as Unreachable would wait for a network the app may be right " +
                "about, and a stall that reaches here as Rejected gets one attempt and no wait — this " +
                "is the trade, and it must be changed on purpose",
            StreamFailure.Reason.Rejected,
            recoveryReasonFrom(address = null, sabrStalled = true, unreachable = true),
        )
    }

    /** A dead or refused ADDRESS is judged first: a 403 arrives as an IO failure too. */
    @Test
    fun `the address has the first word`() {
        assertEquals(
            StreamFailure.Reason.Expired,
            recoveryReasonFrom(StreamFailure.Reason.Expired, sabrStalled = true, unreachable = true),
        )
    }

    /** An ordinary network failure, with no SABR involved, still waits for the network. */
    @Test
    fun `a plain network failure waits for the network`() {
        assertEquals(
            StreamFailure.Reason.Unreachable,
            recoveryReasonFrom(address = null, sabrStalled = false, unreachable = true),
        )
    }

    /** And nothing recoverable stays null, so recovery is not attempted on a decoder fault. */
    @Test
    fun `a failure that nothing could fix is not recoverable`() {
        assertNull(recoveryReasonFrom(address = null, sabrStalled = false, unreachable = false))
    }
}
