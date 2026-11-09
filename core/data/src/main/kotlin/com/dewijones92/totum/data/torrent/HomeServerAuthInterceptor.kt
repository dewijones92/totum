package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

public class HomeServerAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = HomeServerAuth.headersFor(request.url.host)
        if (headers.isEmpty()) {
            if (request.header(HomeServerAuth.HEADER) == null) return chain.proceed(request)
            Diag.log(TAG, "token taken off a request to ${request.url.host}: not the home server")
            return chain.proceed(request.newBuilder().removeHeader(HomeServerAuth.HEADER).build())
        }
        if (request.header(HomeServerAuth.HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().apply { headers.forEach { (k, v) -> header(k, v) } }.build())
    }

    private companion object {
        const val TAG = "auth"
    }
}

public fun OkHttpClient.Builder.sendingTheHomeToken(): OkHttpClient.Builder =
    addNetworkInterceptor(HomeServerAuthInterceptor())
