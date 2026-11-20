package com.dewijones92.totum.data.phone.google

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

public data class GooglePhotosConfig(val clientId: String, val clientSecret: String) {
    public val isSetUp: Boolean get() = clientId.isNotBlank()

    public companion object {
        public const val SCOPE: String = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        public const val AUTH_URL: String = "https://accounts.google.com/o/oauth2/v2/auth"
        public const val TOKEN_URL: String = "https://oauth2.googleapis.com/token"
        public val NOT_SET_UP: GooglePhotosConfig = GooglePhotosConfig("", "")
    }
}

public data class GoogleTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
    override fun toString(): String =
        "GoogleTokens(access=***, refresh=${if (refreshToken == null) "none" else "***"}, expiresAtMs=$expiresAtMs)"
}

public interface GoogleTokenStore {
    public fun load(): GoogleTokens?

    public fun save(tokens: GoogleTokens)

    public fun clear()
}

public class InMemoryGoogleTokenStore(private var held: GoogleTokens? = null) : GoogleTokenStore {
    override fun load(): GoogleTokens? = held

    override fun save(tokens: GoogleTokens) {
        held = tokens
    }

    override fun clear() {
        held = null
    }
}

public object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    public fun newVerifier(): String = ByteArray(VERIFIER_BYTES).also(random::nextBytes).let(encoder::encodeToString)

    public fun challengeOf(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    public fun newState(): String = ByteArray(STATE_BYTES).also(random::nextBytes).let(encoder::encodeToString)

    private const val VERIFIER_BYTES = 48
    private const val STATE_BYTES = 16
}

public object GoogleAuthRequest {
    public fun url(
        config: GooglePhotosConfig,
        redirectUri: String,
        state: String,
        challenge: String,
        base: String = GooglePhotosConfig.AUTH_URL,
    ): String {
        val query = listOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to GooglePhotosConfig.SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "$base?$query"
    }
}

public sealed interface AuthRedirect {
    public data class Code(val code: String) : AuthRedirect

    public data class Refused(val error: String) : AuthRedirect

    public data object WrongState : AuthRedirect

    public data object TimedOut : AuthRedirect
}

public class LoopbackRedirect(private val expectedState: String) : AutoCloseable {

    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    public val redirectUri: String get() = "http://127.0.0.1:${socket.localPort}/"

    @Suppress("TooGenericExceptionCaught")
    public suspend fun await(timeoutMs: Long): AuthRedirect = withContext(Dispatchers.IO) {
        socket.soTimeout = timeoutMs.toInt()
        try {
            socket.accept().use { client ->
                val request = client.getInputStream().bufferedReader().readLine().orEmpty()
                val query = request.substringAfter('?', "").substringBefore(' ')
                val params = query.split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val outcome = when {
                    params["state"] != expectedState -> AuthRedirect.WrongState
                    params["error"] != null -> AuthRedirect.Refused(params.getValue("error"))
                    params["code"] != null -> AuthRedirect.Code(params.getValue("code"))
                    else -> AuthRedirect.Refused("no code in the redirect")
                }
                val page = if (outcome is AuthRedirect.Code) PAGE_OK else PAGE_FAILED
                val body = page.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    write(body)
                    flush()
                }
                Diag.log("google-photos", "the sign-in redirect arrived: ${outcome::class.simpleName}")
                outcome
            }
        } catch (_: SocketTimeoutException) {
            Diag.warn("google-photos", "no sign-in redirect arrived within ${timeoutMs}ms")
            AuthRedirect.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Diag.warn("google-photos", "the sign-in redirect listener failed", failure)
            AuthRedirect.Refused(failure.message ?: "listener failed")
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val PAGE_OK = "<html><body style=\"font-family:sans-serif\"><h2>✅ Signed in to Google Photos</h2>" +
            "<p>You can go back to Totum now.</p></body></html>"
        const val PAGE_FAILED = "<html><body style=\"font-family:sans-serif\"><h2>❌ Sign-in did not finish</h2>" +
            "<p>Go back to Totum and try again.</p></body></html>"
    }
}

public sealed interface GoogleSignIn {
    public data object Done : GoogleSignIn

    public data class Failed(val reason: String) : GoogleSignIn
}

@Serializable
private data class TokenAnswer(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

public class GoogleOAuthClient(
    private val http: OkHttpClient,
    private val config: GooglePhotosConfig,
    private val store: GoogleTokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenUrl: String = GooglePhotosConfig.TOKEN_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun signedIn(): Boolean = store.load() != null

    public fun signOut() {
        store.clear()
        Diag.log("google-photos", "signed out of Google Photos; the stored token is gone")
    }

    public suspend fun exchange(code: String, verifier: String, redirectUri: String): GoogleSignIn {
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()
        return when (val answer = post(body)) {
            is TokenOutcome.Granted -> {
                store.save(answer.tokens)
                Diag.log("google-photos", "signed in: ${answer.tokens}")
                GoogleSignIn.Done
            }
            is TokenOutcome.Refused -> GoogleSignIn.Failed(answer.reason)
        }
    }

    public suspend fun accessToken(): String? {
        val held = store.load() ?: return null
        if (held.expiresAtMs - clock() > EXPIRY_MARGIN_MS) return held.accessToken
        return refreshed(held)
    }

    public suspend fun forceRefresh(): String? = store.load()?.let { refreshed(it) }

    private suspend fun refreshed(held: GoogleTokens): String? {
        val refresh = held.refreshToken ?: run {
            Diag.warn("google-photos", "the access token expired and there is no refresh token; signed out")
            store.clear()
            return null
        }
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        return when (val answer = post(body)) {
            is TokenOutcome.Granted -> {
                val kept = answer.tokens.copy(refreshToken = answer.tokens.refreshToken ?: refresh)
                store.save(kept)
                Diag.log("google-photos", "refreshed the access token: $kept")
                kept.accessToken
            }
            is TokenOutcome.Refused -> {
                Diag.warn("google-photos", "the refresh was refused (${answer.reason}); signed out")
                if (answer.final) store.clear()
                null
            }
        }
    }

    private sealed interface TokenOutcome {
        data class Granted(val tokens: GoogleTokens) : TokenOutcome

        data class Refused(val reason: String, val final: Boolean) : TokenOutcome
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun post(body: FormBody): TokenOutcome = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(tokenUrl).post(body).build()).execute().use { response ->
                val text = response.body.string()
                val answer = runCatching { json.decodeFromString<TokenAnswer>(text) }.getOrNull()
                when {
                    response.isSuccessful && answer?.accessToken != null -> TokenOutcome.Granted(
                        GoogleTokens(
                            answer.accessToken,
                            answer.refreshToken,
                            clock() + (answer.expiresIn ?: DEFAULT_EXPIRES_S) * MS_PER_SECOND,
                        ),
                    )
                    else -> {
                        val reason = answer?.errorDescription ?: answer?.error ?: "HTTP ${response.code}"
                        TokenOutcome.Refused("❌ Google said no: $reason", final = answer?.error == "invalid_grant")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Diag.warn("google-photos", "the token request failed", failure)
            TokenOutcome.Refused("📶 Couldn't reach Google: ${failure.message}", final = false)
        }
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
        const val DEFAULT_EXPIRES_S = 3_600L
        const val MS_PER_SECOND = 1_000L
    }
}
