package com.dewijones92.totum.phonemedia

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.google.AuthRedirect
import com.dewijones92.totum.data.phone.google.BrowserOpener
import com.dewijones92.totum.data.phone.google.GoogleAuthRequest
import com.dewijones92.totum.data.phone.google.GoogleOAuthClient
import com.dewijones92.totum.data.phone.google.GooglePhotosConfig
import com.dewijones92.totum.data.phone.google.GoogleSignIn
import com.dewijones92.totum.data.phone.google.GoogleSignInPort
import com.dewijones92.totum.data.phone.google.GoogleTokenStore
import com.dewijones92.totum.data.phone.google.GoogleTokens
import com.dewijones92.totum.data.phone.google.LoopbackRedirect
import com.dewijones92.totum.data.phone.google.Pkce

class SharedPrefsGoogleTokenStore(context: Context) : GoogleTokenStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): GoogleTokens? {
        val access = prefs.getString(ACCESS, null) ?: return null
        return GoogleTokens(access, prefs.getString(REFRESH, null), prefs.getLong(EXPIRES, 0L))
    }

    override fun save(tokens: GoogleTokens) {
        prefs.edit {
            putString(ACCESS, tokens.accessToken)
            putString(REFRESH, tokens.refreshToken)
            putLong(EXPIRES, tokens.expiresAtMs)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS = "google_photos_auth"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expires_at_ms"
    }
}

class AndroidBrowserOpener(private val context: Context) : BrowserOpener {
    override fun open(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Diag.log("google-photos", "opened the browser at ${url.substringBefore('?')}")
        true
    } catch (missing: ActivityNotFoundException) {
        Diag.warn("google-photos", "no browser could open ${url.substringBefore('?')}", missing)
        false
    }
}

class LoopbackGoogleSignIn(
    private val config: GooglePhotosConfig,
    private val oauth: GoogleOAuthClient,
    private val browser: BrowserOpener,
) : GoogleSignInPort {

    override suspend fun signIn(): GoogleSignIn {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        return LoopbackRedirect(state).use { redirect ->
            val url = GoogleAuthRequest.url(config, redirect.redirectUri, state, Pkce.challengeOf(verifier))
            if (!browser.open(url)) return GoogleSignIn.Failed("🌐 No browser could open Google's sign-in page")
            when (val back = redirect.await(SIGN_IN_TIMEOUT_MS)) {
                is AuthRedirect.Code -> oauth.exchange(back.code, verifier, redirect.redirectUri)
                is AuthRedirect.Refused -> GoogleSignIn.Failed("🚫 Google sign-in was refused: ${back.error}")
                AuthRedirect.WrongState -> GoogleSignIn.Failed(
                    "🚫 Google sign-in came back with the wrong state; try again"
                )
                AuthRedirect.TimedOut -> GoogleSignIn.Failed("⌛ Google sign-in timed out; try again")
            }
        }
    }

    private companion object {
        const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L
    }
}
