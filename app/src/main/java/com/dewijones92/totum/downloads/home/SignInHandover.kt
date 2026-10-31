package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJobsApi
import com.dewijones92.totum.innertube.auth.OAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SignInHandover(private val tokens: suspend () -> OAuthTokens?) {
    private val lock = Mutex()
    private var done = false

    suspend fun ensure(home: HomeJobsApi) = lock.withLock {
        if (done) return@withLock
        val current = tokens() ?: return@withLock Diag.log(TAG, "not signed in to YouTube here; nothing to hand over")
        val answer = home.handOver(
            AccountHandover(current.accessToken.value, current.refreshToken.value, current.expiresAtEpochSeconds),
        )
        done = answer is HomeAnswer.Ok
        Diag.log(TAG, "YouTube sign-in handed to home: ${if (done) "ok" else answer}")
    }

    fun forget() {
        done = false
    }
}
