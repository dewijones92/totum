package com.dewijones92.totum

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.DefaultAppContainer
import com.dewijones92.totum.exsurge.ExsurgeEvent
import com.dewijones92.totum.notifications.NewContentWorker

class TotumApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash during the rest of startup is still captured.
        container.installCrashReporting()
        // Fetch the latest yt-dlp in the background so YouTube-breaking changes
        // self-heal; the download applies on the next start.
        container.refreshExtractorEngine()
        // Mirror video watch-progress to YouTube as playback advances.
        container.startWatchHistorySync()
        // Load the account's subscribed channels (read live, never copied locally).
        container.refreshSubscriptions()
        // Keep the queue listenable offline: fetch each queued item's audio.
        container.startQueueAutoDownload()
        container.startDownloadNotifications()
        // Periodically check every subscription (both pillars) and notify on new content.
        NewContentWorker.schedule(this)
        container.exsurge.dispatch(ExsurgeEvent.Tick, "startup")
    }

    /**
     * The one image loader the whole app shares (used by every [MediaThumbnail]).
     * Network fetching goes through OkHttp — the coil-network-okhttp artifact
     * registers it automatically — so thumbnails ride the same HTTP stack the
     * rest of the app uses. Crossfade so images fade in rather than pop.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()
}
