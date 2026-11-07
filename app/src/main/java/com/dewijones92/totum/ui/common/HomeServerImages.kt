package com.dewijones92.totum.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dewijones92.totum.data.torrent.HomeServerAuthInterceptor
import okhttp3.OkHttpClient

internal fun homeServerImageClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(HomeServerAuthInterceptor())
    .build()

internal fun homeServerAwareImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { homeServerImageClient() })) }
        .crossfade(true)
        .build()
