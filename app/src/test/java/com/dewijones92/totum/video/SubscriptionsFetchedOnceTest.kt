package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.subscriptions.SubscribedChannel
import com.dewijones92.totum.innertube.subscriptions.SubscriptionsResult
import com.dewijones92.totum.innertube.subscriptions.YouTubeSubscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1,594 channels over two pages is not a thing to fetch twice for no reason.
 *
 * Two unrelated callers ask for the list — `AppContainer` at launch, `VideosViewModel` around the
 * Videos tab — and in report 0.1.383 both fired 21 seconds apart, so the whole list came down
 * twice in one 96-second session. The second told us nothing the first had not.
 *
 * The freshness window must not get in the way of the cases where the answer has genuinely
 * changed, which is why signing in and out force it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsFetchedOnceTest {

    private val dispatcher = StandardTestDispatcher()
    private var clock = 1_000L
    private var listCalls = 0

    private val subscriptions = object : YouTubeSubscriptions {
        override suspend fun list(): SubscriptionsResult {
            listCalls++
            return SubscriptionsResult.Success(
                listOf(
                    SubscribedChannel(
                        channelId = "UCwarfronts",
                        title = "WarFronts",
                        channelUrl = HttpUrl.of("https://www.youtube.com/channel/UCwarfronts"),
                        avatarUrl = null,
                    ),
                ),
            )
        }
    }

    /** Signed in until a test clears the store, which is what signing out actually does. */
    private val tokens = InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), Long.MAX_VALUE))
    private val account = YouTubeAccount(FakeYouTubeAuth(), tokens, nowEpochSeconds = { 0 })

    /**
     * Its own scope over the test dispatcher, NOT `backgroundScope`: background work is background
     * precisely so `advanceUntilIdle` does not wait for it, and a coroutine that never gets
     * dispatched looks exactly like the code under test doing nothing.
     */
    private fun subs() = AccountSubscriptions(
        subscriptions = subscriptions,
        actions = FakeYouTubeActions(),
        account = account,
        scope = CoroutineScope(dispatcher),
        now = { clock },
    )

    @Test
    fun `launch and tab entry together fetch the list once`() = runTest(dispatcher) {
        val subs = subs()

        subs.refresh()
        advanceUntilIdle()
        clock += 21_000 // the gap in the real report
        subs.refresh()
        advanceUntilIdle()

        assertEquals(1, listCalls)
    }

    /** Two callers at the same instant must not both start a fetch either. */
    @Test
    fun `two refreshes at once do not both fetch`() = runTest(dispatcher) {
        val subs = subs()

        subs.refresh()
        subs.refresh()
        advanceUntilIdle()

        assertEquals(1, listCalls)
    }

    /** Once the window has passed, it is allowed to be current again. */
    @Test
    fun `after the window it refetches`() = runTest(dispatcher) {
        val subs = subs()

        subs.refresh()
        advanceUntilIdle()
        clock += 120_000
        subs.refresh()
        advanceUntilIdle()

        assertEquals(2, listCalls)
    }

    /** Signing in changes the answer, so the age of the last one is beside the point. */
    @Test
    fun `a forced refresh ignores the window`() = runTest(dispatcher) {
        val subs = subs()

        subs.refresh()
        advanceUntilIdle()
        subs.refresh(force = true)
        advanceUntilIdle()

        assertEquals(2, listCalls)
    }

    /**
     * A feed coming back signed-out wants one boolean, not 1,594 channels. Three call sites were
     * paying the whole fetch to learn it.
     */
    @Test
    fun `re-checking the account does not refetch the list when nothing changed`() = runTest(dispatcher) {
        val subs = subs()
        subs.refresh()
        advanceUntilIdle()

        subs.recheckSignedIn()
        advanceUntilIdle()

        assertEquals(1, listCalls)
    }

    /** But when it HAS changed, the list must follow — a stale list is worse than a slow one. */
    @Test
    fun `re-checking does reload when the account has actually signed out`() = runTest(dispatcher) {
        val subs = subs()
        subs.refresh()
        advanceUntilIdle()

        tokens.clear()
        subs.recheckSignedIn()
        advanceUntilIdle()

        assertEquals("signed out means the list is emptied, not refetched", 1, listCalls)
        assertEquals(emptyList<Any>(), subs.channels.value)
        assertEquals(false, subs.signedIn.value)
    }

    /** A transient failure must not buy a minute of silence — the next caller should try again. */
    @Test
    fun `a failed fetch does not start the freshness window`() = runTest(dispatcher) {
        var fail = true
        val flaky = object : YouTubeSubscriptions {
            override suspend fun list(): SubscriptionsResult {
                listCalls++
                return if (fail) SubscriptionsResult.Failure("no network") else SubscriptionsResult.Success(emptyList())
            }
        }
        val subs = AccountSubscriptions(flaky, FakeYouTubeActions(), account, CoroutineScope(dispatcher)) { clock }

        subs.refresh()
        advanceUntilIdle()
        fail = false
        subs.refresh()
        advanceUntilIdle()

        assertEquals(2, listCalls)
    }
}
