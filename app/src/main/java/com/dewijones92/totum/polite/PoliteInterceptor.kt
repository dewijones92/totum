package com.dewijones92.totum.polite

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.common.RequestMeter
import okhttp3.Interceptor
import okhttp3.Response

class PoliteInterceptor(
    private val gate: PoliteGate,
    private val meter: RequestMeter,
    private val maxRetryWaitMs: Long = MAX_RETRY_WAIT_MS,
    private val gated: (String) -> Boolean = ::isYouTubeApi,
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!gated(host)) {
            return chain.proceed(request).also { meter.record(host, it.code) }
        }
        val what = "$host${request.url.encodedPath}"
        val first = gate.pass(what) { chain.proceed(request) }.also { meter.record(host, it.code) }
        if (first.code != HTTP_TOO_MANY_REQUESTS) return first.also { if (it.isSuccessful) gate.served() }
        gate.tooManyRequests(what, first.header("Retry-After")?.toLongOrNull()?.times(MILLIS_PER_SECOND))
        val pausedMs = (gate.slowDown.value?.untilMs ?: 0) - now()
        if (pausedMs > maxRetryWaitMs) {
            Diag.log(
                "polite",
                "$what not retried: the pause (${pausedMs / MILLIS_PER_SECOND}s) is longer than a caller should wait"
            )
            return first
        }
        first.close()
        Diag.log("polite", "$what retried once after the pause")
        return gate.pass(what) { chain.proceed(request) }.also {
            meter.record(host, it.code)
            if (it.isSuccessful) gate.served()
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MILLIS_PER_SECOND = 1000L
        private const val MAX_RETRY_WAIT_MS = 35_000L

        fun isYouTubeApi(host: String): Boolean =
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtubei.googleapis.com" ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }
}
