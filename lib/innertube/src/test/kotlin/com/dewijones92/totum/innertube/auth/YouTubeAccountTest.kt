package com.dewijones92.totum.innertube.auth

import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountTest {

    private val auth = FakeYouTubeAuth()
    private val store = InMemoryTokenStore()
    private var signedOutTimes = 0
    private val account = YouTubeAccount(
        auth,
        store,
        nowEpochSeconds = { NOW },
        onSignedOut = { signedOutTimes++ },
    )

    /**
     * There are TWO ways to lose an account and only one is a button, so anything scoped to the
     * account has to hear about both. A revoked refresh token clears the store from inside
     * [YouTubeAccount.accessToken] with no UI involved; something wired to the sign-out screen
     * alone would go on comparing the old account's figures against the next account's.
     */
    @Test
    fun `signing out says so`() = runTest {
        store.save(FRESH)

        account.signOut()

        assertEquals(1, signedOutTimes)
    }

    @Test
    fun `a revoked refresh token says so too`() = runTest {
        store.save(STALE)
        auth.refreshResult = TokenRefreshResult.Revoked

        assertEquals(AccessTokenResult.SignedOut, account.accessToken())
        assertEquals("a dead grant is a sign-out, and must be announced as one", 1, signedOutTimes)
    }

    @Test
    fun `a successful sign-in is persisted`() = runTest {
        auth.pollResults.add(TokenPollResult.Authorized(FRESH))

        account.signIn().toList()

        assertEquals(FRESH, store.load())
        assertTrue(account.isSignedIn())
    }

    @Test
    fun `a failed sign-in persists nothing`() = runTest {
        auth.pollResults.add(TokenPollResult.Denied)

        account.signIn().toList()

        assertNull(store.load())
        assertFalse(account.isSignedIn())
    }

    @Test
    fun `sign-out clears the store`() = runTest {
        store.save(FRESH)

        account.signOut()

        assertFalse(account.isSignedIn())
    }

    @Test
    fun `a live token is served without touching the network`() = runTest {
        store.save(FRESH)

        val result = account.accessToken()

        assertEquals(AccessTokenResult.Available(FRESH.accessToken), result)
    }

    @Test
    fun `a stale token is refreshed and the refresh persisted`() = runTest {
        store.save(STALE)
        val renewed = FRESH.copy(accessToken = AccessToken("renewed"))
        auth.refreshResult = TokenRefreshResult.Refreshed(renewed)

        val result = account.accessToken()

        assertEquals(AccessTokenResult.Available(AccessToken("renewed")), result)
        assertEquals(renewed, store.load())
    }

    @Test
    fun `a token expiring within the safety margin is treated as stale`() = runTest {
        store.save(FRESH.copy(expiresAtEpochSeconds = NOW + 30))
        auth.refreshResult = TokenRefreshResult.Refreshed(FRESH)

        val result = account.accessToken()

        assertEquals(AccessTokenResult.Available(FRESH.accessToken), result)
    }

    @Test
    fun `a revoked grant signs the user out`() = runTest {
        store.save(STALE)
        auth.refreshResult = TokenRefreshResult.Revoked

        val result = account.accessToken()

        assertEquals(AccessTokenResult.SignedOut, result)
        assertNull(store.load())
    }

    @Test
    fun `a refresh failure is surfaced but keeps the sign-in`() = runTest {
        store.save(STALE)
        auth.refreshResult = TokenRefreshResult.Failure("offline")

        val result = account.accessToken()

        assertEquals(AccessTokenResult.Failure("offline"), result)
        assertEquals(STALE, store.load())
    }

    @Test
    fun `no tokens means signed out`() = runTest {
        assertEquals(AccessTokenResult.SignedOut, account.accessToken())
    }

    private companion object {
        const val NOW = 1_000_000L
        val FRESH = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = NOW + 3_600)
        val STALE = OAuthTokens(AccessToken("old"), RefreshToken("rt"), expiresAtEpochSeconds = NOW - 1)
    }
}
