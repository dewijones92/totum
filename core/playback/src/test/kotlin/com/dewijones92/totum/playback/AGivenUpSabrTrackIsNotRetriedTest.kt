package com.dewijones92.totum.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A track SABR has given up on must fail at once; everything else must still be retried.
 *
 * Measured on totum-api35, 2026-08-20, AFTER the wasted-bandwidth half was already fixed: raising the
 * refusal as a plain IOException left Media3's default policy retrying the load ten times with
 * exponential backoff — 3600ms, 5605, 8611, 12613, 17618, 22632, 27646, 32652, 37656 — so the
 * extraction fallback that works did not begin for about thirty-eight seconds. To a listener that IS
 * the stall, and it survived the fix that stopped the bandwidth being wasted.
 *
 * The other half matters just as much, and is why this is not simply "stop retrying": a timeout, a 5xx
 * and a dropped connection are ordinary and worth another go, and they are most of what the policy is
 * for. So is [SabrPrematureEndException], which means the opposite of giving up — a fresh conversation
 * IS worth having, and conflating the two would stop recovery working.
 */
class AGivenUpSabrTrackIsNotRetriedTest {

    @Test
    fun `a refusal is recognised`() {
        assertTrue(
            "a refusal that is not recognised is retried for about thirty-eight seconds",
            SabrGaveUpException("SABR served nothing").isSabrGivingUp(),
        )
    }

    @Test
    fun `a refusal wrapped by media3 is still recognised`() {
        assertTrue(
            "Media3 wraps a DataSource.open failure before the policy sees it, so a refusal only " +
                "recognised at the top of the chain is not recognised at all",
            IOException("Unable to connect", SabrGaveUpException("SABR served nothing")).isSabrGivingUp(),
        )
    }

    @Test
    fun `an ordinary timeout is not a refusal`() {
        assertFalse(
            "a timeout must keep its retry — dropping it turns every hiccup into a re-resolve",
            SocketTimeoutException("read timed out").isSabrGivingUp(),
        )
    }

    @Test
    fun `stopping short is not giving up`() {
        assertFalse(
            "SabrPrematureEndException means a FRESH conversation is worth having, which is the " +
                "opposite of giving up",
            SabrPrematureEndException("stopped short").isSabrGivingUp(),
        )
    }

    /**
     * A circular chain must not hang the loader thread.
     *
     * Two nodes rather than one: `initCause` refuses self-causation outright, so the reachable cycle
     * is A -> B -> A. That also shows which guard does the work — the `!==` check only catches direct
     * self-reference, and it is the DEPTH BOUND that survives this.
     */
    @Test
    fun `a circular cause chain terminates`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertFalse("walking the cause chain must be bounded", first.isSabrGivingUp())
    }

    /** A chain deeper than the walk is not a refusal, and must not be reported as one. */
    @Test
    fun `a refusal buried deeper than the walk is not claimed`() {
        var deep: IOException = SabrGaveUpException("SABR served nothing")
        repeat(DEEPER_THAN_THE_WALK) { deep = IOException("wrapper", deep) }

        assertFalse(
            "the bound is real and the answer must stay honest about it rather than guessing",
            deep.isSabrGivingUp(),
        )
    }

    private companion object {
        const val DEEPER_THAN_THE_WALK = 12
    }
}
