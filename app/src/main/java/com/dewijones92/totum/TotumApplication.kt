package com.dewijones92.totum

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.dewijones92.totum.cast.startCastScreens
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.DefaultAppContainer
import com.dewijones92.totum.diagnostics.MainThreadWatch
import com.dewijones92.totum.exsurge.ExsurgeEvent
import com.dewijones92.totum.exsurge.FlatPhoneNudge
import com.dewijones92.totum.groups.GroupNewsWorker
import com.dewijones92.totum.notifications.NewContentWorker
import com.dewijones92.totum.playback.startGapless
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.NetworkStatus
import com.dewijones92.totum.stats.ListenRecorder
import com.dewijones92.totum.stats.recordFinishedDownloads
import com.dewijones92.totum.uploads.NewUploadsWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class TotumApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash during the rest of startup is still captured.
        container.installCrashReporting()
        MainThreadWatch().start(this)
        signHomeServerRequests(container.appPreferences)
        // Fetch the latest yt-dlp in the background so YouTube-breaking changes
        // self-heal; the download applies on the next start.
        container.refreshExtractorEngine()
        container.applicationScope.launch { container.appUpdate.keepChecking(UPDATE_CHECK_INTERVAL) }
        // Mirror video watch-progress to YouTube as playback advances.
        container.startWatchHistorySync()
        // Load the account's subscribed channels (read live, never copied locally).
        container.refreshSubscriptions()
        // Keep the queue listenable offline: fetch each queued item's audio.
        container.startQueueAutoDownload()
        container.applicationScope.launch {
            combine(container.pinStore.pins, container.recentActions.recent) { pins, recent -> pins to recent }
                .collect { (pins, recent) -> container.homeScreenShortcuts?.publish(pins, recent) }
        }
        container.startDownloadNotifications()
        container.listeningSwitcher.start()
        startGapless(container, NetworkStatus(this)::isMetered)
        startCastScreens(container, this)
        // Periodically check every subscription (both pillars) and notify on new content.
        NewContentWorker.schedule(this)
        NewUploadsWorker.schedule(this)
        GroupNewsWorker.schedule(this)
        container.applicationScope.launch { container.groupNews.warm() }
        val metered = NetworkStatus(this)::isMetered
        container.applicationScope.launch {
            delay(UPLOADS_CHECK_DELAY_MS)
            if (!warmEngineAfterLaunch) {
                Diag.log("uploads", "not checking for new uploads on opening: running under instrumentation")
            } else if (metered()) {
                Diag.log("uploads", "app opened on a metered network: not checking for new uploads")
            } else {
                runCatching { container.newUploads.check("app opened") }
                    .onFailure { Diag.warn("uploads", "check on opening failed", it) }
            }
            if (warmEngineAfterLaunch) {
                runCatching { container.groupNews.check(container.sourceGroupStore.observeGroups().first()) }
                    .onFailure { Diag.warn("group", "group news check on opening failed", it) }
            }
        }
        container.exsurge.dispatch(ExsurgeEvent.Tick, "startup")
        FlatPhoneNudge(this, container.exsurge, container.applicationScope, container.statsLog).start()
        ListenRecorder(
            container.playbackController,
            container.playbackQueue,
            container.statsLog,
            container.applicationScope
        ).start()
        container.applicationScope.launch {
            recordFinishedDownloads(container.downloadManager.observeDownloads(), container.statsLog)
        }
        container.dailyAlarms.tickAll("startup")
        warmTheEngineSoon()
    }

    private fun warmTheEngineSoon() {
        if (!warmEngineAfterLaunch) {
            Diag.log("engine", "not warming the engine after launch: running under instrumentation")
            return
        }
        container.applicationScope.launch {
            delay(ENGINE_WARM_DELAY_MS)
            Diag.log("engine", "warming the engine ${ENGINE_WARM_DELAY_MS}ms after launch, so the first video does not")
            runCatching { container.ytDlpEngine.warmUp() }
                .onFailure { Diag.warn("engine", "warm-up failed; the first video will start the engine itself", it) }
        }
    }

    companion object {
        @Volatile
        internal var warmEngineAfterLaunch: Boolean = true
        private const val ENGINE_WARM_DELAY_MS = 10_000L
        private const val UPLOADS_CHECK_DELAY_MS = 20_000L
        private val UPDATE_CHECK_INTERVAL = 30.minutes
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

private fun signHomeServerRequests(prefs: AppPreferences) {
    HomeServerAuth.use(
        host = { prefs.settings.value.homeServerBase.takeIf { it.isNotBlank() }?.let { "totum.$it" } },
        token = { prefs.settings.value.homeServerToken },
    )
    val settings = prefs.settings.value
    Diag.log(
        "auth",
        "home server requests carry this device's token as a header " +
            "[server=${settings.homeServerBase.isNotBlank()} signedIn=${settings.homeServerToken.isNotBlank()}]",
    )
}
