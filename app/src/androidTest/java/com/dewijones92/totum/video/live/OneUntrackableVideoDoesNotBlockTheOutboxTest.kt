package com.dewijones92.totum.video.live

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.fake.InMemoryAccountProgressOutbox
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.history.HttpYouTubeWatchHistory
import com.dewijones92.totum.innertube.history.SessionResult
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.video.ProgressOutboxDrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the real account: one video YouTube will not track must not strand everything behind it.
 *
 * This is the flow report 0.1.496 proved was broken and the one the unit tests can only model.
 * Three videos YouTube gave this app no tracking for sat at the head of Dewi's outbox and **123
 * perfectly sendable updates never got a turn** — the same three ids, thirty-seven times, in one
 * session. `ProgressOutboxDrainTest` covers the rule against a fake; nothing covered it against
 * YouTube, which is the only thing that can say what "no tracking" actually looks like.
 *
 * MANUAL ONLY, and registered as such: it needs a device that is SIGNED IN (see CLAUDE.md's
 * emulator section), so it assumes rather than asserts that and skips gracefully anywhere else.
 *
 * The untrackable id is a made-up one rather than one of Dewi's three, and that choice turned out
 * to matter: probing those three against the live account on 2026-09-20 found **all three now
 * answer with tracking**. Their refusal was temporary. That measurement is why nothing in the
 * outbox is ever dropped for failing — a write-off would have destroyed real listening — and a
 * test built on them would have quietly stopped testing anything. A video id that does not exist
 * cannot change its mind.
 */
class OneUntrackableVideoDoesNotBlockTheOutboxTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val history by lazy {
        HttpYouTubeWatchHistory(
            app.container.youTubeAccount,
            http,
            InnerTubeClient(http),
            HttpSignatureTimestampSource(http),
        )
    }

    @After
    fun close(): Unit = scope.cancel()

    private fun signedIn(): Boolean = runBlocking {
        app.container.youTubeAccount.accessToken() is AccessTokenResult.Available
    }

    /**
     * Our own half: a real video opens a session, and a video that cannot be tracked does not
     * bring the answer down with it. What YouTube SAYS is printed rather than asserted — this
     * repository has gone red six times for asserting someone else's policy.
     */
    @Test
    fun aVideoThatCannotBeTrackedDoesNotLookLikeABrokenSender() = runBlocking {
        assumeTrue("this device is not signed in — run SignInOnThisDeviceTest first", signedIn())

        val good = history.beginSession(PLAYABLE_ID)
        val bad = history.beginSession(NO_SUCH_ID)
        println("[outbox-live] $PLAYABLE_ID -> $good")
        println("[outbox-live] $NO_SUCH_ID -> $bad")

        assertEquals("a real video must still open a session", SessionResult.Opened, good)
        assertTrue("and a video that cannot be tracked must not report the sender broken", bad !is SessionResult.Opened)
    }

    /** And the queue drains past it, which is the whole bug from report 0.1.496. */
    @Test
    fun theSendableRowsBehindItStillGo() = runBlocking {
        assumeTrue("this device is not signed in — run SignInOnThisDeviceTest first", signedIn())
        val outbox = InMemoryAccountProgressOutbox()
        outbox.record(row(NO_SUCH_ID, at = 1))
        outbox.record(row(PLAYABLE_ID, at = 2))

        ProgressOutboxDrain(outbox, history, scope).drain()

        assertEquals(
            "the real video's progress must reach the account even though a dud is ahead of it",
            listOf(NO_SUCH_ID),
            outbox.pending().map { it.itemId.value },
        )
        assertTrue("and the dud is kept, not dropped", outbox.pending().isNotEmpty())
    }

    private fun row(id: String, at: Long) = PendingAccountProgress(
        itemId = MediaItemId(id),
        positionMs = 30_000,
        durationMs = 600_000,
        finished = false,
        recordedAtEpochMs = at,
    )

    private companion object {
        /** Creative-commons, long-lived, and already used as a fixture elsewhere in this suite. */
        const val PLAYABLE_ID = "aqz-KE-bpKQ"

        /** Eleven characters, correctly shaped, and not a video — so YouTube can only refuse it. */
        const val NO_SUCH_ID = "zzzzzzzzzzz"
    }
}
