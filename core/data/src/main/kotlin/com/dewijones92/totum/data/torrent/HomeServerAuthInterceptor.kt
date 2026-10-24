package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.HomeServerAuth
import okhttp3.Interceptor
import okhttp3.Response

public class HomeServerAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HomeServerAuth.HEADER) != null) return chain.proceed(request)
        val headers = HomeServerAuth.headersFor(request.url.host)
        if (headers.isEmpty()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().apply { headers.forEach { (k, v) -> header(k, v) } }.build())
    }
}
