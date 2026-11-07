package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJobsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.innertube.auth.OAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SignInHandover(
    private val tokens: suspend () -> OAuthTokens?,
    private val identity: suspend () -> Requester,
    private val tell: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var done = false
    private var refused: String? = null

    suspend fun ensure(home: HomeJobsApi) = lock.withLock {
        if (done) return@withLock
        refused?.let { return@withLock Diag.log(TAG, "not handing the sign-in over again: the home server said $it") }
        val current = tokens() ?: return@withLock Diag.log(TAG, "not signed in to YouTube here; nothing to hand over")
        val from = identity()
        val answer = home.handOver(
            AccountHandover(
                current.accessToken.value,
                current.refreshToken.value,
                current.expiresAtEpochSeconds,
                from,
            ),
        )
        done = answer is HomeAnswer.Ok
        if (answer is HomeAnswer.No && answer.reason.startsWith(AccountHandover.REFUSED_MARK)) {
            refused = answer.reason
            tell(answer.reason)
        }
        Diag.log(TAG, "YouTube sign-in handed to home as ${from.label} (${from.id}): ${if (done) "ok" else answer}")
    }

    fun forget() {
        done = false
        refused = null
    }
}
