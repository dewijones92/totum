package com.dewijones92.totum.account

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TokenStore] backed by a private [android.content.SharedPreferences] file.
 * The app-side storage the library's port asks for; the sign-in survives
 * restarts. IO hops off the main thread.
 */
class SharedPrefsTokenStore(
    context: Context,
    /**
     * The prefs file, overridable so a TEST can never touch the real sign-in.
     *
     * It could, and did: the durability test below calls `clear()` in its teardown, which on the shared
     * default file signs the device out. On a device that had been signed in by hand -- a device code
     * someone walked to another machine to approve -- that is a genuinely expensive thing for a test to
     * do, and nothing about running a test says "sign me out".
     */
    prefsName: String = DEFAULT_PREFS,
) : TokenStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun load(): OAuthTokens? = withContext(Dispatchers.IO) {
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, -1L)
        if (access == null || refresh == null || expiresAt < 0) {
            null
        } else {
            OAuthTokens(AccessToken(access), RefreshToken(refresh), expiresAt)
        }
    }

    /**
     * COMMITTED, not applied. `edit {}` defaults to `apply()`, which writes to memory and schedules the
     * disk write -- so `isSignedIn()` answered true while nothing was persisted, and a process ending
     * before the flush lost the sign-in without a word. Measured on 2026-08-19: a device-code sign-in
     * completed on the emulator and left no `youtube_account.xml` behind at all, throwing away an
     * approval that Dewi had typed by hand and that a single-use code cannot repeat.
     *
     * Blocking is right for this one write: it is rare, it costs a person walking to another device, and
     * this store is already on [Dispatchers.IO]. See ASignInSurvivesTheProcessTest.
     */
    override suspend fun save(tokens: OAuthTokens): Unit = withContext(Dispatchers.IO) {
        prefs.edit(commit = true) {
            putString(KEY_ACCESS, tokens.accessToken.value)
            putString(KEY_REFRESH, tokens.refreshToken.value)
            putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochSeconds)
        }
    }

    /** Committed for the same reason: a sign-OUT that does not survive the process is a sign-out that lies. */
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs.edit(commit = true) { clear() }
    }

    public companion object {
        /** The real sign-in's file. Tests pass their own name instead. */
        public const val DEFAULT_PREFS: String = "youtube_account"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
