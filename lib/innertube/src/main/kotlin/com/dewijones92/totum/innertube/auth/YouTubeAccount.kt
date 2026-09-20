package com.dewijones92.totum.innertube.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * The one object the app talks to about "the signed-in YouTube account":
 * runs sign-ins (persisting the outcome), signs out, and hands every
 * InnerTube call a live access token — transparently refreshing a stale one.
 */
public class YouTubeAccount(
    private val auth: YouTubeAuth,
    private val store: TokenStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    /**
     * Run whenever the account goes away, so whatever else is scoped to it can be dropped.
     *
     * Here rather than at the sign-out button because there are **two** ways to lose an account
     * and only one of them is a button: a refresh answered `invalid_grant` clears the store from
     * inside [accessToken], with nothing in the UI involved. Anything wired to the button alone
     * would survive a revoked token and go on being compared against the next account's.
     */
    private val onSignedOut: suspend () -> Unit = {},
) {

    /** A [DeviceLogin] run whose success is persisted to the [TokenStore]. */
    public fun signIn(): Flow<DeviceLoginEvent> = DeviceLogin(auth).start()
        .onEach { event ->
            if (event is DeviceLoginEvent.Succeeded) store.save(event.tokens)
        }

    public suspend fun signOut() {
        store.clear()
        onSignedOut()
    }

    public suspend fun isSignedIn(): Boolean = store.load() != null

    /**
     * The access token every authenticated call starts from. Refreshes (and
     * re-persists) when within [EXPIRY_MARGIN_SECONDS] of expiry so a token
     * can't lapse mid-request; a dead grant clears the store — the user is
     * signed out and must pair again.
     */
    public suspend fun accessToken(): AccessTokenResult {
        val tokens = store.load() ?: return AccessTokenResult.SignedOut
        if (!tokens.isExpired(nowEpochSeconds() + EXPIRY_MARGIN_SECONDS)) {
            return AccessTokenResult.Available(tokens.accessToken)
        }
        return when (val refreshed = auth.refreshTokens(tokens)) {
            is TokenRefreshResult.Refreshed -> {
                store.save(refreshed.tokens)
                AccessTokenResult.Available(refreshed.tokens.accessToken)
            }
            TokenRefreshResult.Revoked -> {
                store.clear()
                onSignedOut()
                AccessTokenResult.SignedOut
            }
            is TokenRefreshResult.Failure -> AccessTokenResult.Failure(refreshed.detail)
        }
    }

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}

public sealed interface AccessTokenResult {
    public data class Available(val token: AccessToken) : AccessTokenResult
    public data object SignedOut : AccessTokenResult
    public data class Failure(val detail: String) : AccessTokenResult
}
