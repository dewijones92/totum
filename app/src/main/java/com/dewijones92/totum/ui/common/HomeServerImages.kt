package com.dewijones92.totum.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dewijones92.totum.data.torrent.sendingTheHomeToken
import com.dewijones92.totum.phonemedia.PhoneThumbnailFetcher
import okhttp3.OkHttpClient

internal fun homeServerImageClient(): OkHttpClient = OkHttpClient.Builder()
    .sendingTheHomeToken()
    .build()

internal fun homeServerAwareImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(PhoneThumbnailFetcher.Factory(context))
            add(OkHttpNetworkFetcherFactory(callFactory = { homeServerImageClient() }))
        }
        .crossfade(true)
        .build()
