package com.dewijones92.totum.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.kidlock.TvConnection
import com.dewijones92.totum.kidlock.TvConnector
import com.dewijones92.totum.ui.cast.needsLocalNetwork
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MediaRouterTvConnector(
    private val context: Context,
    private val castDevice: StateFlow<String?>,
) : TvConnector {

    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
        )
        .build()

    override fun scan(): Flow<List<String>> = callbackFlow {
        val router = MediaRouter.getInstance(context)
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(namesOn(router))
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(namesOn(router))
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(namesOn(router))
            }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        trySend(namesOn(router))
        Diag.log(TAG, "looking for cast TVs: ${namesOn(router)} already known")
        awaitClose { router.removeCallback(callback) }
    }.flowOn(Dispatchers.Main)

    override suspend fun connect(tvName: String): TvConnection = withContext(Dispatchers.Main) {
        if (needsLocalNetwork(context)) {
            Diag.warn(TAG, "cannot look for $tvName: Android has not granted local network access")
            return@withContext TvConnection.NeedsLocalNetwork
        }
        val router = MediaRouter.getInstance(context)
        val callback = object : MediaRouter.Callback() {}
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        try {
            val route = withTimeoutOrNull(FIND_MS) {
                var found: MediaRouter.RouteInfo? = null
                while (found == null) {
                    found = castRoutes(router).firstOrNull { it.name.equals(tvName, ignoreCase = true) }
                    if (found == null) delay(POLL_MS)
                }
                found
            }
            if (route == null) {
                val seen = namesOn(router)
                Diag.warn(TAG, "no cast route named $tvName within ${FIND_MS}ms; routes seen: $seen")
                return@withContext TvConnection.NotFound(seen)
            }
            Diag.log(TAG, "selecting the cast route ${route.name} (${route.id}) [castDevice=${castDevice.value}]")
            router.selectRoute(route)
            val device = withTimeoutOrNull(START_MS) {
                castDevice.first { it != null && it.equals(route.name, ignoreCase = true) }
            }
            if (device == null) {
                Diag.warn(TAG, "selected ${route.name} but no cast session started within ${START_MS}ms")
                TvConnection.Failed("the TV did not start a cast session in ${START_MS / MS_PER_SECOND} s")
            } else {
                TvConnection.Connected(device)
            }
        } finally {
            router.removeCallback(callback)
        }
    }

    private fun castRoutes(router: MediaRouter): List<MediaRouter.RouteInfo> =
        router.routes.filter { it.matchesSelector(selector) }

    private fun namesOn(router: MediaRouter): List<String> = castRoutes(router).map { it.name }.distinct().sorted()

    private companion object {
        const val TAG = "cast"
        const val FIND_MS = 20_000L
        const val START_MS = 20_000L
        const val POLL_MS = 500L
        const val MS_PER_SECOND = 1_000L
    }
}
